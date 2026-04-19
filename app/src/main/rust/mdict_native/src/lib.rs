use mdict_rs::{MddFile, MdxFile};
use once_cell::sync::Lazy;
use serde_json::json;
use std::collections::{HashMap, HashSet, VecDeque};
use std::ffi::{CStr, CString};
use std::fs::{self, File};
use std::io::{BufRead, BufReader, BufWriter, Read, Write};
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
const LOOKUP_INDEX_BIN_MAGIC: u32 = 0x4D49_4458; // "MIDX"
const LOOKUP_INDEX_BIN_VERSION: u32 = 1;

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

fn replace_last_char(input: &str, ch: char) -> Option<String> {
    let mut chars: Vec<char> = input.chars().collect();
    if chars.is_empty() {
        return None;
    }
    chars.pop();
    chars.push(ch);
    Some(chars.into_iter().collect::<String>())
}

fn expand_i_stem_candidates(input: &str) -> Vec<String> {
    // 連用形(i段) -> 終止形(u段), e.g. 残し -> 残す
    const I_TO_U: &[(char, char)] = &[
        ('い', 'う'),
        ('き', 'く'),
        ('ぎ', 'ぐ'),
        ('し', 'す'),
        ('ち', 'つ'),
        ('に', 'ぬ'),
        ('び', 'ぶ'),
        ('み', 'む'),
        ('り', 'る'),
        ('じ', 'ず'),
    ];

    let mut out = Vec::<String>::new();
    let char_count = input.chars().count();
    if char_count < 2 {
        return out;
    }
    if let Some(last) = input.chars().last() {
        for (from, to) in I_TO_U {
            if last == *from {
                if let Some(next) = replace_last_char(input, *to) {
                    out.push(next);
                }
                break;
            }
        }
    }
    // Ichidan stem fallback (食べ -> 食べる)
    out.push(format!("{input}る"));
    out
}

fn build_deinflected_variants(candidate: &str) -> Vec<String> {
    #[derive(Clone, Copy)]
    struct Rule {
        from: &'static str,
        to: &'static str,
    }

    // Yomitan/Luna-style rule-driven chain (degraded without POS constraints).
    const RULES: &[Rule] = &[
        Rule { from: "ませんでした", to: "" },
        Rule { from: "ました", to: "" },
        Rule { from: "ません", to: "" },
        Rule { from: "ます", to: "" },
        Rule { from: "なかった", to: "る" },
        Rule { from: "ない", to: "る" },
        Rule { from: "てしまった", to: "る" },
        Rule { from: "てしまう", to: "る" },
        Rule { from: "ている", to: "る" },
        Rule { from: "でいる", to: "る" },
        Rule { from: "られた", to: "る" },
        Rule { from: "れた", to: "る" },
        Rule { from: "られる", to: "る" },
        Rule { from: "れる", to: "る" },
        Rule { from: "させる", to: "る" },
        Rule { from: "せる", to: "る" },
        Rule { from: "した", to: "する" },
        Rule { from: "して", to: "する" },
        Rule { from: "できる", to: "する" },
        Rule { from: "だった", to: "だ" },
        Rule { from: "です", to: "だ" },
        Rule { from: "のです", to: "" },
        Rule { from: "のだ", to: "" },
        Rule { from: "こと", to: "" },
        Rule { from: "もの", to: "" },
        Rule { from: "の", to: "" },
        Rule { from: "た", to: "る" },
    ];

    let src = candidate.trim();
    if src.is_empty() {
        return Vec::new();
    }

    let mut out = Vec::<String>::new();
    let mut seen = HashSet::<String>::new();
    let mut queue = VecDeque::<(String, usize)>::new();

    seen.insert(src.to_string());
    queue.push_back((src.to_string(), 0));

    while let Some((cur, depth)) = queue.pop_front() {
        out.push(cur.clone());
        if depth >= 3 {
            continue;
        }

        for rule in RULES {
            if cur.ends_with(rule.from) {
                let mut next = cur[..cur.len() - rule.from.len()].to_string();
                next.push_str(rule.to);
                let trimmed = next.trim().to_string();
                if !trimmed.is_empty() && seen.insert(trimmed.clone()) {
                    queue.push_back((trimmed, depth + 1));
                }
            }
        }

        // Polite stem and i-stem restoration (important for 残し -> 残す)
        let mut stems = Vec::<String>::new();
        for tail in ["ませんでした", "ました", "ません", "ます"] {
            if let Some(prefix) = cur.strip_suffix(tail) {
                let p = prefix.trim();
                if !p.is_empty() {
                    stems.push(p.to_string());
                }
            }
        }
        stems.push(cur.clone());

        for stem in stems {
            for next in expand_i_stem_candidates(&stem) {
                let trimmed = next.trim().to_string();
                if !trimmed.is_empty() && seen.insert(trimmed.clone()) {
                    queue.push_back((trimmed, depth + 1));
                }
            }
        }
    }

    out
}

#[derive(Clone)]
struct LookupCandidate {
    text: String,
    scan_rank: i32,
    deinflect_rank: i32,
}

fn build_hoshi_like_candidates(query: &str, scan_length: usize) -> Vec<LookupCandidate> {
    let mut out = Vec::<LookupCandidate>::new();
    let mut seen = HashMap::<String, (i32, i32)>::new();
    let scan_candidates = build_scan_candidates(query, scan_length);

    for (scan_rank, scan) in scan_candidates.iter().enumerate() {
        for (deinflect_rank, variant) in build_deinflected_variants(scan).iter().enumerate() {
            let sr = scan_rank as i32;
            let dr = deinflect_rank as i32;
            let entry = seen.entry(variant.clone()).or_insert((sr, dr));
            if (sr, dr) < *entry {
                *entry = (sr, dr);
            }
        }
    }
    for (text, (scan_rank, deinflect_rank)) in seen.into_iter() {
        out.push(LookupCandidate {
            text,
            scan_rank,
            deinflect_rank,
        });
    }
    out.sort_by(|a, b| {
        a.scan_rank
            .cmp(&b.scan_rank)
            .then(a.deinflect_rank.cmp(&b.deinflect_rank))
            .then(b.text.chars().count().cmp(&a.text.chars().count()))
    });
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

    let parsed_entries = match load_lookup_entries_binary(entries_path) {
        Ok(entries) => entries,
        Err(_) => {
            let file = File::open(entries_path).map_err(|e| format!("open entries failed: {e}"))?;
            let reader = BufReader::new(file);
            let mut entries = Vec::<NativeEntry>::new();
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
                entries.push(NativeEntry {
                    term,
                    reading,
                    definition,
                    normalized_term,
                });
            }
            let _ = save_lookup_entries_binary(entries_path, &entries);
            entries
        }
    };

    let built = Arc::new(build_lookup_maps(parsed_entries)?);
    LOOKUP_CACHE
        .lock()
        .map_err(|_| "lookup cache poisoned".to_string())?
        .insert(entries_path.to_string(), built.clone());
    Ok(built)
}

fn load_lookup_index_from_mdx(mdx_path: &str, cache_key: &str) -> Result<Arc<LookupIndex>, String> {
    let normalized_cache = cache_key.trim();
    let cache_id = if normalized_cache.is_empty() {
        format!("mdx::{mdx_path}")
    } else {
        format!("mdx::{normalized_cache}")
    };
    if let Some(cached) = LOOKUP_CACHE
        .lock()
        .map_err(|_| "lookup cache poisoned".to_string())?
        .get(&cache_id)
        .cloned()
    {
        return Ok(cached);
    }

    let mdx = MdxFile::open(mdx_path).map_err(|e| format!("open mdx failed: {e}"))?;

    let mut entries = Vec::<NativeEntry>::new();
    let mut by_exact = HashMap::<String, Vec<usize>>::new();

    for item in mdx.entries() {
        let record = match item {
            Ok(v) => v,
            Err(_) => continue,
        };
        let term = record.key.trim().to_string();
        if term.is_empty() {
            continue;
        }
        let definition = record.text.trim().to_string();
        let normalized_term = normalize_lookup(&term);
        if normalized_term.is_empty() {
            continue;
        }
        let index = entries.len();
        entries.push(NativeEntry {
            term,
            reading: String::new(),
            definition,
            normalized_term: normalized_term.clone(),
        });
        by_exact.entry(normalized_term).or_default().push(index);
    }

    if entries.is_empty() {
        return Err("mdx has no valid terms".to_string());
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
        .insert(cache_id, built.clone());
    Ok(built)
}

fn lookup_with_index(
    index: &LookupIndex,
    query: &str,
    max_results: usize,
    scan_length: usize,
) -> serde_json::Value {
    let query_candidates = build_hoshi_like_candidates(query, scan_length);
    if query_candidates.is_empty() {
        return json!({ "results": [] });
    }

    let mut emitted: Vec<(usize, i32, i32, i32)> = Vec::new();
    let mut used = std::collections::HashSet::<usize>::new();
    let mut has_exact = false;

    for candidate in &query_candidates {
        let normalized = normalize_lookup(&candidate.text);
        if normalized.is_empty() {
            continue;
        }

        let candidate_len = candidate.text.chars().count() as i32;

        if let Some(exact_indices) = index.by_exact.get(&normalized) {
            for idx in exact_indices {
                if used.insert(*idx) {
                    emitted.push((
                        *idx,
                        candidate_len.max(1),
                        candidate.scan_rank,
                        candidate.deinflect_rank,
                    ));
                }
                if emitted.len() >= max_results {
                    break;
                }
            }
            if !exact_indices.is_empty() {
                has_exact = true;
            }
        }
        if emitted.len() >= max_results {
            break;
        }
    }

    // Fallback: if no exact hit at all, allow prefix expansion (degraded behavior).
    if !has_exact {
        for candidate in &query_candidates {
            let normalized = normalize_lookup(&candidate.text);
            if normalized.is_empty() {
                continue;
            }
            let candidate_len = candidate.text.chars().count() as i32;
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
                            emitted.push((
                                *idx,
                                candidate_len.max(1),
                                candidate.scan_rank + 1000,
                                candidate.deinflect_rank + 1000,
                            ));
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
    } else if emitted.len() > max_results {
        emitted.truncate(max_results);
    }

    emitted.sort_by(|a, b| {
        // Yomitan-like ordering (without POS/frequency data):
        // matched length desc -> preprocess(scan) asc -> deinflect trace asc
        b.1.cmp(&a.1).then(a.2.cmp(&b.2)).then(a.3.cmp(&b.3))
    });
    if emitted.len() > max_results {
        emitted.truncate(max_results);
    }

    let mut results = Vec::new();
    for (idx, matched_len, scan_rank, deinflect_rank) in emitted {
        if let Some(entry) = index.entries.get(idx) {
            // frequency is unavailable in plain MDX; use 0 as degraded fallback.
            let frequency_rank = 0i32;
            let score = (matched_len * 10_000)
                - (scan_rank * 100)
                - deinflect_rank
                - frequency_rank;
            let resolved_definition = render_definition_with_entry_link(index, &entry.definition);
            results.push(json!({
                "term": entry.term,
                "reading": entry.reading,
                "definition": resolved_definition,
                "matchedLength": matched_len,
                "score": score.max(1)
            }));
        }
    }

    json!({ "results": results })
}

fn index_cache_sidecar_path(entries_path: &str) -> String {
    format!("{entries_path}.idxbin")
}

fn write_u32(writer: &mut dyn Write, value: u32) -> Result<(), String> {
    writer
        .write_all(&value.to_le_bytes())
        .map_err(|e| format!("write u32 failed: {e}"))
}

fn write_u64(writer: &mut dyn Write, value: u64) -> Result<(), String> {
    writer
        .write_all(&value.to_le_bytes())
        .map_err(|e| format!("write u64 failed: {e}"))
}

fn read_u32(reader: &mut dyn Read) -> Result<u32, String> {
    let mut buf = [0u8; 4];
    reader
        .read_exact(&mut buf)
        .map_err(|e| format!("read u32 failed: {e}"))?;
    Ok(u32::from_le_bytes(buf))
}

fn read_u64(reader: &mut dyn Read) -> Result<u64, String> {
    let mut buf = [0u8; 8];
    reader
        .read_exact(&mut buf)
        .map_err(|e| format!("read u64 failed: {e}"))?;
    Ok(u64::from_le_bytes(buf))
}

fn write_string(writer: &mut dyn Write, value: &str) -> Result<(), String> {
    let bytes = value.as_bytes();
    write_u32(writer, bytes.len() as u32)?;
    writer
        .write_all(bytes)
        .map_err(|e| format!("write string failed: {e}"))
}

fn read_string(reader: &mut dyn Read) -> Result<String, String> {
    let len = read_u32(reader)? as usize;
    if len > 16 * 1024 * 1024 {
        return Err("string too large".to_string());
    }
    let mut buf = vec![0u8; len];
    reader
        .read_exact(&mut buf)
        .map_err(|e| format!("read string failed: {e}"))?;
    String::from_utf8(buf).map_err(|e| format!("invalid utf8 string: {e}"))
}

fn save_lookup_entries_binary(entries_path: &str, entries: &[NativeEntry]) -> Result<(), String> {
    let source_meta = fs::metadata(entries_path).map_err(|e| format!("entries metadata failed: {e}"))?;
    let source_size = source_meta.len();
    let source_mtime = source_meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(0);

    let sidecar = index_cache_sidecar_path(entries_path);
    let temp = format!("{sidecar}.tmp");
    let mut writer =
        BufWriter::new(File::create(&temp).map_err(|e| format!("create sidecar temp failed: {e}"))?);

    write_u32(&mut writer, LOOKUP_INDEX_BIN_MAGIC)?;
    write_u32(&mut writer, LOOKUP_INDEX_BIN_VERSION)?;
    write_u64(&mut writer, source_size)?;
    write_u64(&mut writer, source_mtime)?;
    write_u32(&mut writer, entries.len() as u32)?;
    for entry in entries {
        write_string(&mut writer, &entry.term)?;
        write_string(&mut writer, &entry.reading)?;
        write_string(&mut writer, &entry.definition)?;
        write_string(&mut writer, &entry.normalized_term)?;
    }
    writer
        .flush()
        .map_err(|e| format!("flush sidecar failed: {e}"))?;

    let _ = fs::remove_file(&sidecar);
    fs::rename(&temp, &sidecar).map_err(|e| format!("rename sidecar failed: {e}"))?;
    Ok(())
}

fn load_lookup_entries_binary(entries_path: &str) -> Result<Vec<NativeEntry>, String> {
    let source_meta = fs::metadata(entries_path).map_err(|e| format!("entries metadata failed: {e}"))?;
    let source_size = source_meta.len();
    let source_mtime = source_meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(0);

    let sidecar = index_cache_sidecar_path(entries_path);
    let mut reader = BufReader::new(File::open(&sidecar).map_err(|e| format!("open sidecar failed: {e}"))?);

    let magic = read_u32(&mut reader)?;
    if magic != LOOKUP_INDEX_BIN_MAGIC {
        return Err("sidecar magic mismatch".to_string());
    }
    let version = read_u32(&mut reader)?;
    if version != LOOKUP_INDEX_BIN_VERSION {
        return Err("sidecar version mismatch".to_string());
    }
    let cached_size = read_u64(&mut reader)?;
    let cached_mtime = read_u64(&mut reader)?;
    if cached_size != source_size || cached_mtime != source_mtime {
        return Err("sidecar source changed".to_string());
    }

    let count = read_u32(&mut reader)? as usize;
    if count == 0 || count > 5_000_000 {
        return Err("sidecar entry count invalid".to_string());
    }

    let mut entries = Vec::<NativeEntry>::with_capacity(count);
    for _ in 0..count {
        let term = read_string(&mut reader)?;
        let reading = read_string(&mut reader)?;
        let definition = read_string(&mut reader)?;
        let normalized_term = read_string(&mut reader)?;
        if term.trim().is_empty() || normalized_term.trim().is_empty() {
            continue;
        }
        entries.push(NativeEntry {
            term,
            reading,
            definition,
            normalized_term,
        });
    }
    if entries.is_empty() {
        return Err("sidecar has no valid entries".to_string());
    }
    Ok(entries)
}

fn build_lookup_maps(entries: Vec<NativeEntry>) -> Result<LookupIndex, String> {
    if entries.is_empty() {
        return Err("entries file has no valid terms".to_string());
    }
    let mut by_exact = HashMap::<String, Vec<usize>>::new();
    for (index, entry) in entries.iter().enumerate() {
        if entry.normalized_term.is_empty() {
            continue;
        }
        by_exact
            .entry(entry.normalized_term.clone())
            .or_default()
            .push(index);
    }
    if by_exact.is_empty() {
        return Err("entries file has no valid terms".to_string());
    }
    let mut normalized_terms_sorted: Vec<String> = by_exact.keys().cloned().collect();
    normalized_terms_sorted.sort();
    normalized_terms_sorted.dedup();
    Ok(LookupIndex {
        entries,
        by_exact,
        normalized_terms_sorted,
    })
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
        Ok(lookup_with_index(index.as_ref(), &query, max_results, scan_length))
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
pub extern "C" fn mdict_native_extract_mdd_json(
    mdd_path: *const c_char,
    output_dir: *const c_char,
) -> *mut c_char {
    let result = (|| {
        let mdd_path = c_ptr_to_string(mdd_path)?;
        let output_dir = c_ptr_to_string(output_dir)?;
        if !Path::new(&mdd_path).is_file() {
            return Err("mdd file not found".to_string());
        }
        if output_dir.trim().is_empty() {
            return Err("output dir is empty".to_string());
        }
        fs::create_dir_all(&output_dir).map_err(|e| format!("create output dir failed: {e}"))?;

        let mdd = MddFile::open(&mdd_path).map_err(|e| format!("open mdd failed: {e}"))?;
        let mut media_count: u64 = 0;
        let mut errors: Vec<String> = Vec::new();

        for item in mdd.entries() {
            match item {
                Ok(record) => {
                    let rel = record.key.replace('\\', "/").trim_start_matches('/').to_string();
                    if rel.is_empty() {
                        continue;
                    }
                    let target = Path::new(&output_dir).join(&rel);
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
                            if let Err(e) = file.write_all(record.data.as_ref()) {
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

        Ok(json!({
            "success": media_count > 0,
            "mediaCount": media_count,
            "errors": errors
        }))
    })();

    match result {
        Ok(value) => make_json_ptr(value),
        Err(error) => make_json_ptr(json!({
            "success": false,
            "mediaCount": 0,
            "error": error
        })),
    }
}

#[no_mangle]
pub extern "C" fn mdict_native_lookup_mdx_json(
    mdx_path: *const c_char,
    cache_key: *const c_char,
    query: *const c_char,
    max_results: i32,
    scan_length: i32,
) -> *mut c_char {
    let result = (|| {
        let mdx_path = c_ptr_to_string(mdx_path)?;
        let cache_key = c_ptr_to_string(cache_key).unwrap_or_default();
        let query = c_ptr_to_string(query)?;
        if mdx_path.trim().is_empty() {
            return Err("mdx path is empty".to_string());
        }
        if query.trim().is_empty() {
            return Ok(json!({ "results": [] }));
        }
        let max_results = max_results.max(1) as usize;
        let scan_length = scan_length.max(1) as usize;
        let index = load_lookup_index_from_mdx(&mdx_path, &cache_key)?;
        Ok(lookup_with_index(index.as_ref(), &query, max_results, scan_length))
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
