use mdict_rs::{MddFile, MdxFile};
use once_cell::sync::Lazy;
use serde_json::json;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::fs::{self, File};
use std::io::{BufRead, BufReader, BufWriter, Write};
use std::os::raw::c_char;
use std::path::Path;
use std::sync::{Arc, Mutex};

#[derive(Clone)]
struct NativeEntry {
    term: String,
    reading: String,
    definition: String,
    normalized_term: String,
}

struct LookupIndex {
    entries: Vec<NativeEntry>,
    by_exact: HashMap<String, Vec<usize>>,
    normalized_terms_sorted: Vec<String>,
}

static LOOKUP_CACHE: Lazy<Mutex<HashMap<String, Arc<LookupIndex>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

fn c_ptr_to_string(ptr: *const c_char) -> Result<String, String> {
    if ptr.is_null() {
        return Err("null pointer".to_string());
    }
    let cstr = unsafe { CStr::from_ptr(ptr) };
    cstr.to_str()
        .map(|s| s.to_string())
        .map_err(|e| format!("invalid utf8 input: {e}"))
}

fn make_json_ptr(value: serde_json::Value) -> *mut c_char {
    let text = value.to_string();
    match CString::new(text) {
        Ok(c) => c.into_raw(),
        Err(_) => CString::new("{\"success\":false,\"error\":\"json serialization failed\"}")
            .expect("static string has no NUL")
            .into_raw(),
    }
}

fn normalize_lookup(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for ch in value.chars().flat_map(|c| c.to_lowercase()) {
        if ch.is_whitespace() || ch.is_ascii_punctuation() || ch.is_ascii_control() || ch.is_ascii_graphic() && ch.is_ascii_punctuation() {
            continue;
        }
        out.push(ch);
    }
    out
}

fn build_scan_candidates(query: &str, scan_length: usize) -> Vec<String> {
    let chars: Vec<char> = query.chars().collect();
    if chars.is_empty() {
        return Vec::new();
    }
    let max_len = chars.len().min(scan_length.max(1));
    let mut out = Vec::new();
    for len in (1..=max_len).rev() {
        let candidate: String = chars[..len].iter().collect();
        let trimmed = candidate.trim();
        if !trimmed.is_empty() {
            out.push(trimmed.to_string());
        }
    }
    out
}

fn build_deinflected_variants(candidate: &str) -> Vec<String> {
    let mut out = Vec::<String>::new();
    let src = candidate.trim();
    if src.is_empty() {
        return out;
    }
    out.push(src.to_string());

    // Lightweight JP tail stripping to mimic Hoshi/Yomitan-style fallback path.
    // This is intentionally conservative and ordered from longest to shortest.
    const TAILS: [&str; 24] = [
        "のではない", "なければならない", "てしまった", "てしまう", "ている", "でいる", "でした", "ます", "ました",
        "ません", "ない", "なかった", "だった", "だろう", "でしょう", "のだ", "のです", "の", "こと", "もの",
        "する", "した", "して", "た",
    ];
    let mut current = src.to_string();
    for _ in 0..4 {
        let mut stripped = false;
        for tail in TAILS {
            if current.ends_with(tail) {
                let next = current.trim_end_matches(tail).trim().to_string();
                if !next.is_empty() && !out.iter().any(|v| v == &next) {
                    out.push(next.clone());
                }
                current = next;
                stripped = true;
                break;
            }
        }
        if !stripped || current.is_empty() {
            break;
        }
    }
    out
}

fn build_hoshi_like_candidates(query: &str, scan_length: usize) -> Vec<String> {
    let mut out = Vec::<String>::new();
    let mut seen = std::collections::HashSet::<String>::new();
    let scan_candidates = build_scan_candidates(query, scan_length);

    for scan in &scan_candidates {
        for variant in build_deinflected_variants(scan) {
            if seen.insert(variant.clone()) {
                out.push(variant);
            }
        }
    }
    out
}

fn lower_bound(sorted: &[String], target: &str) -> usize {
    let mut low = 0usize;
    let mut high = sorted.len();
    while low < high {
        let mid = (low + high) / 2;
        if sorted[mid].as_str() < target {
            low = mid + 1;
        } else {
            high = mid;
        }
    }
    low
}

fn load_lookup_index(entries_path: &str) -> Result<Arc<LookupIndex>, String> {
    if let Some(cached) = LOOKUP_CACHE
        .lock()
        .map_err(|_| "lookup cache poisoned".to_string())?
        .get(entries_path)
        .cloned()
    {
        return Ok(cached);
    }

    let file = File::open(entries_path).map_err(|e| format!("open entries failed: {e}"))?;
    let reader = BufReader::new(file);
    let mut entries = Vec::<NativeEntry>::new();
    let mut by_exact = HashMap::<String, Vec<usize>>::new();

    for line in reader.lines() {
        let line = line.map_err(|e| format!("read entries failed: {e}"))?;
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let json: serde_json::Value =
            serde_json::from_str(trimmed).map_err(|e| format!("parse entry json failed: {e}"))?;
        let term = json
            .get("term")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .trim()
            .to_string();
        if term.is_empty() {
            continue;
        }
        let reading = json
            .get("reading")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .trim()
            .to_string();
        let definition = json
            .get("definition")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .trim()
            .to_string();
        let normalized_term = normalize_lookup(&term);
        if normalized_term.is_empty() {
            continue;
        }
        let index = entries.len();
        entries.push(NativeEntry {
            term,
            reading,
            definition,
            normalized_term: normalized_term.clone(),
        });
        by_exact.entry(normalized_term).or_default().push(index);
    }

    if entries.is_empty() {
        return Err("entries file has no valid terms".to_string());
    }

    let mut normalized_terms_sorted: Vec<String> = by_exact.keys().cloned().collect();
    normalized_terms_sorted.sort();
    normalized_terms_sorted.dedup();

    let built = Arc::new(LookupIndex {
        entries,
        by_exact,
        normalized_terms_sorted,
    });
    LOOKUP_CACHE
        .lock()
        .map_err(|_| "lookup cache poisoned".to_string())?
        .insert(entries_path.to_string(), built.clone());
    Ok(built)
}

fn parse_mdx_link_target(raw: &str) -> Option<String> {
    let trimmed = raw.trim_start();
    if !trimmed.starts_with("@@@LINK=") {
        return None;
    }
    let target = trimmed
        .trim_start_matches("@@@LINK=")
        .replace('\0', "")
        .lines()
        .next()
        .unwrap_or_default()
        .trim()
        .to_string();
    if target.is_empty() {
        None
    } else {
        Some(target)
    }
}

fn resolve_linked_definition(index: &LookupIndex, initial_definition: &str, max_depth: usize) -> String {
    let mut current = initial_definition.trim().to_string();
    if current.is_empty() {
        return current;
    }
    let mut visited = std::collections::HashSet::<String>::new();

    for _ in 0..max_depth {
        let Some(target) = parse_mdx_link_target(&current) else {
            break;
        };
        if !visited.insert(target.clone()) {
            break;
        }
        let normalized_target = normalize_lookup(&target);
        if normalized_target.is_empty() {
            break;
        }
        let Some(indices) = index.by_exact.get(&normalized_target) else {
            break;
        };
        let Some(first_idx) = indices.first().copied() else {
            break;
        };
        let Some(next_entry) = index.entries.get(first_idx) else {
            break;
        };
        let next = next_entry.definition.trim();
        if next.is_empty() {
            break;
        }
        current = next.to_string();
    }
    current
}

fn render_definition_with_entry_link(index: &LookupIndex, raw_definition: &str) -> String {
    let trimmed = raw_definition.trim();
    let Some(_) = parse_mdx_link_target(trimmed) else {
        return trimmed.to_string();
    };
    // Keep only resolved body content to avoid duplicate headword rendering in UI.
    resolve_linked_definition(index, trimmed, 8)
}

#[no_mangle]
pub extern "C" fn mdict_native_import_json(
    mdx_path: *const c_char,
    output_dir: *const c_char,
) -> *mut c_char {
    let result = (|| {
        let mdx_path = c_ptr_to_string(mdx_path)?;
        let output_dir = c_ptr_to_string(output_dir)?;
        if !Path::new(&mdx_path).is_file() {
            return Err("mdx file not found".to_string());
        }
        if output_dir.trim().is_empty() {
            return Err("output dir is empty".to_string());
        }
        fs::create_dir_all(&output_dir).map_err(|e| format!("create output dir failed: {e}"))?;

        let mdx = MdxFile::open(&mdx_path).map_err(|e| format!("open mdx failed: {e}"))?;
        let header = mdx.header();
        let title = header
            .title
            .clone()
            .unwrap_or_default()
            .trim()
            .to_string();
        let entries_path = Path::new(&output_dir).join("entries.ndjson");
        let entries_file = File::create(&entries_path)
            .map_err(|e| format!("create entries file failed: {e}"))?;
        let mut writer = BufWriter::new(entries_file);
        let mut term_count: u64 = 0;
        let mut errors: Vec<String> = Vec::new();

        for item in mdx.entries() {
            match item {
                Ok(record) => {
                    let term = record.key.trim();
                    if term.is_empty() {
                        continue;
                    }
                    let definition = record.text.trim().to_string();
                    serde_json::to_writer(
                        &mut writer,
                        &json!({
                            "term": term,
                            "reading": "",
                            "definition": definition
                        }),
                    )
                    .map_err(|e| format!("write entry json failed: {e}"))?;
                    writer
                        .write_all(b"\n")
                        .map_err(|e| format!("write entry newline failed: {e}"))?;
                    term_count = term_count.saturating_add(1);
                }
                Err(e) => {
                    if errors.len() < 16 {
                        errors.push(format!("decode entry failed: {e}"));
                    }
                }
            }
        }
        writer
            .flush()
            .map_err(|e| format!("flush entries file failed: {e}"))?;

        // Best-effort media export from sibling .mdd (same basename as mdx).
        let mut media_count: u64 = 0;
        let media_dir = Path::new(&output_dir).join("media");
        let mdd_path = Path::new(&mdx_path).with_extension("mdd");
        if mdd_path.is_file() {
            fs::create_dir_all(&media_dir)
                .map_err(|e| format!("create media dir failed: {e}"))?;
            let mdd = MddFile::open(&mdd_path).map_err(|e| format!("open mdd failed: {e}"))?;
            for item in mdd.entries() {
                match item {
                    Ok(resource) => {
                        let mut rel = resource.key.trim().trim_start_matches('/').trim_start_matches('\\').to_string();
                        if rel.is_empty() {
                            continue;
                        }
                        rel = rel.replace('\\', "/");
                        let target = media_dir.join(&rel);
                        if let Some(parent) = target.parent() {
                            if let Err(e) = fs::create_dir_all(parent) {
                                if errors.len() < 16 {
                                    errors.push(format!("create media parent failed: {e}"));
                                }
                                continue;
                            }
                        }
                        match File::create(&target) {
                            Ok(mut file) => {
                                if let Err(e) = file.write_all(&resource.data) {
                                    if errors.len() < 16 {
                                        errors.push(format!("write media failed: {e}"));
                                    }
                                    continue;
                                }
                                media_count = media_count.saturating_add(1);
                            }
                            Err(e) => {
                                if errors.len() < 16 {
                                    errors.push(format!("create media file failed: {e}"));
                                }
                            }
                        }
                    }
                    Err(e) => {
                        if errors.len() < 16 {
                            errors.push(format!("decode media failed: {e}"));
                        }
                    }
                }
            }
        }

        if term_count == 0 && errors.is_empty() {
            errors.push("no valid entries decoded".to_string());
        }

        Ok(json!({
            "success": term_count > 0,
            "title": title,
            "termCount": term_count,
            "mediaCount": media_count,
            "entriesFile": entries_path.to_string_lossy(),
            "errors": errors
        }))
    })();

    match result {
        Ok(value) => make_json_ptr(value),
        Err(error) => make_json_ptr(json!({
            "success": false,
            "title": "",
            "termCount": 0,
            "errors": [error]
        })),
    }
}

#[no_mangle]
pub extern "C" fn mdict_native_lookup_json(
    entries_path: *const c_char,
    query: *const c_char,
    max_results: i32,
    scan_length: i32,
) -> *mut c_char {
    let result = (|| {
        let entries_path = c_ptr_to_string(entries_path)?;
        let query = c_ptr_to_string(query)?;
        if entries_path.trim().is_empty() {
            return Err("entries path is empty".to_string());
        }
        if query.trim().is_empty() {
            return Ok(json!({ "results": [] }));
        }
        if !Path::new(&entries_path).is_file() {
            return Err("entries file not found".to_string());
        }

        let max_results = max_results.max(1) as usize;
        let scan_length = scan_length.max(1) as usize;
        let index = load_lookup_index(&entries_path)?;
        let query_candidates = build_hoshi_like_candidates(&query, scan_length);
        if query_candidates.is_empty() {
            return Ok(json!({ "results": [] }));
        }

        let mut emitted: Vec<(usize, i32)> = Vec::new();
        let mut used = std::collections::HashSet::<usize>::new();

        for candidate in &query_candidates {
            let normalized = normalize_lookup(candidate);
            if normalized.is_empty() {
                continue;
            }

            let candidate_len = candidate.chars().count() as i32;

            if let Some(exact_indices) = index.by_exact.get(&normalized) {
                for idx in exact_indices {
                    if used.insert(*idx) {
                        emitted.push((*idx, candidate_len.max(1)));
                    }
                    if emitted.len() >= max_results {
                        break;
                    }
                }
            }
            if emitted.len() >= max_results {
                break;
            }

            let start = lower_bound(&index.normalized_terms_sorted, &normalized);
            for term in index.normalized_terms_sorted.iter().skip(start) {
                if !term.starts_with(&normalized) {
                    break;
                }
                if term == &normalized {
                    continue;
                }
                if let Some(prefix_indices) = index.by_exact.get(term) {
                    for idx in prefix_indices {
                        if used.insert(*idx) {
                            emitted.push((*idx, candidate_len.max(1)));
                        }
                        if emitted.len() >= max_results {
                            break;
                        }
                    }
                }
                if emitted.len() >= max_results {
                    break;
                }
            }
            if emitted.len() >= max_results {
                break;
            }
        }

        let mut results = Vec::new();
        for (idx, matched_len) in emitted {
            if let Some(entry) = index.entries.get(idx) {
                let exact = normalize_lookup(query_candidates.first().map(String::as_str).unwrap_or_default())
                    == entry.normalized_term;
                let score = if exact { 120 } else { 92 };
                let resolved_definition = render_definition_with_entry_link(index.as_ref(), &entry.definition);
                results.push(json!({
                    "term": entry.term,
                    "reading": entry.reading,
                    "definition": resolved_definition,
                    "matchedLength": matched_len,
                    "score": score
                }));
            }
        }

        Ok(json!({ "results": results }))
    })();

    match result {
        Ok(value) => make_json_ptr(value),
        Err(error) => make_json_ptr(json!({
            "results": [],
            "error": error
        })),
    }
}

#[no_mangle]
pub extern "C" fn mdict_native_clear_lookup_cache() {
    if let Ok(mut guard) = LOOKUP_CACHE.lock() {
        guard.clear();
    }
}

#[no_mangle]
pub extern "C" fn mdict_native_free_string(ptr: *mut c_char) {
    if ptr.is_null() {
        return;
    }
    unsafe {
        let _ = CString::from_raw(ptr);
    }
}
