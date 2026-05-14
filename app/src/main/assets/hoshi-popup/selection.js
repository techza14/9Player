//
//  selection.js
//  Hoshi Reader
//
//  Copyright © 2026 Manhhao.
//  SPDX-License-Identifier: GPL-3.0-or-later
//

window.hoshiSelection = {
    selection: null,
    highlightLayer: null,
    scanDelimiters: '。、！？…‥「」『』（）()【】〈〉《》〔〕｛｝{}［］[]・：；:;，,.─\n\r',
    sentenceDelimiters: '。！？.!?\n\r',
    trailingSentenceChars: '。、！？…‥」』）)】〉》〕｝}］]',
    brackets: {'「':'」', '『': '』', '（':'）', '(':')', '【':'】', '〈':'〉', '《':'》', '〔':'〕', '｛':'｝', '{':'}', '［':'］', '[':']'},

    isVertical() {
        return window.getComputedStyle(document.body).writingMode === "vertical-rl";
    },
    
    isScanBoundary(char) {
        return /^[\s\u3000]$/.test(char) || this.scanDelimiters.includes(char);
    },
    
    isFurigana(node) {
        const el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
        return !!el?.closest('rt, rp');
    },
    
    findParagraph(node) {
        let el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
        return el?.closest('p, .glossary-content') || null;
    },
    
    createWalker(rootNode) {
        const root = rootNode || document.body;
        
        return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: (n) => this.isFurigana(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
        });
    },
    
    inCharRange(charRange, x, y) {
        const rects = charRange.getClientRects();
        if (rects.length) {
            for (const rect of rects) {
                if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                    return true;
                }
            }
            return false;
        }
        const rect = charRange.getBoundingClientRect();
        return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    },
    
    getCaretRange(x, y) {
        if (document.caretPositionFromPoint) {
            const pos = document.caretPositionFromPoint(x, y);
            if (!pos) {
                return null;
            }
            
            const range = document.createRange();
            range.setStart(pos.offsetNode, pos.offset);
            range.collapse(true);
            return range;
        } else {
            const element = document.elementFromPoint(x, y);
            if (!element) {
                return null;
            }
            
            const container = element.closest('p, div, span, ruby, a') || document.body;
            const walker = this.createWalker(container);
            
            const range = document.createRange();
            let node;
            while (node = walker.nextNode()) {
                for (let i = 0; i < node.textContent.length; i++) {
                    range.setStart(node, i);
                    range.setEnd(node, i + 1);
                    if (this.inCharRange(range, x, y)) {
                        range.collapse(true);
                        return range;
                    }
                }
            }
            return document.caretRangeFromPoint(x, y);
        }
    },
    
    getCharacterAtPoint(x, y) {
        const range = this.getCaretRange(x, y);
        if (!range) {
            return null;
        }
        
        const node = range.startContainer;
        if (node.nodeType !== Node.TEXT_NODE) {
            return null;
        }
        
        if (this.isFurigana(node)) {
            return null;
        }
        
        const text = node.textContent;
        const caret = range.startOffset;
        
        for (const offset of [caret, caret - 1, caret + 1]) {
            if (offset < 0 || offset >= text.length) {
                continue;
            }
            
            const charRange = document.createRange();
            charRange.setStart(node, offset);
            charRange.setEnd(node, offset + 1);
            if (this.inCharRange(charRange, x, y)) {
                if (this.isScanBoundary(text[offset])) {
                    return null;
                }
                return { node, offset };
            }
        }
        
        return null;
    },
    
    getSentenceContext(startNode, startOffset) {
        const container = this.findParagraph(startNode) || document.body;
        const walker = this.createWalker(container);
        
        walker.currentNode = startNode;
        const partsBefore = [];
        let node = startNode;
        let limit = startOffset;
        
        while (node) {
            const text = node.textContent;
            let foundStart = false;
            for (let i = limit - 1; i >= 0; i--) {
                if (this.sentenceDelimiters.includes(text[i])) {
                    partsBefore.push(text.slice(i + 1, limit));
                    foundStart = true;
                    break;
                }
            }
            
            if (foundStart) {
                break;
            }
            
            partsBefore.push(text.slice(0, limit));
            node = walker.previousNode();
            if (node) limit = node.textContent.length;
        }
        
        walker.currentNode = startNode;
        const partsAfter = [];
        node = startNode;
        let start = startOffset;
        
        while (node) {
            const text = node.textContent;
            let foundEnd = false;
            
            for (let i = start; i < text.length; i++) {
                if (this.sentenceDelimiters.includes(text[i])) {
                    let end = i + 1;
                    
                    while (end < text.length) {
                        if (!this.trailingSentenceChars.includes(text[end])) break;
                        end += 1;
                    }
                    partsAfter.push(text.slice(start, end));
                    foundEnd = true;
                    break;
                }
            }
            
            if (foundEnd) {
                break;
            }
            
            partsAfter.push(text.slice(start));
            
            node = walker.nextNode();
            start = 0;
        }
        
        const beforeText = partsBefore.reverse().join('');
        const rawSentence = beforeText + partsAfter.join('');
        const leadingTrim = rawSentence.length - rawSentence.trimStart().length;
        let selectedOffset = Math.max(0, beforeText.length - leadingTrim);
        let sentence = rawSentence.trim();

        const closeBrackets = new Set(Object.values(this.brackets));
        const openBrackets = new Set(Object.keys(this.brackets));
        let stack = [];
        let unmatchedClose = [];
        
        for (let i = 0; i < sentence.length; i++) {
            const ch = sentence[i];
            if (openBrackets.has(ch)) {
                stack.push(ch);
            } else if (closeBrackets.has(ch)) {
                if (stack.length > 0 && this.brackets[stack[stack.length-1]] === ch) {
                    stack.pop();
                } else {
                    unmatchedClose.push(ch);
                }
            }
        }

        let startSlice = 0;
        while (stack.length > 0 && startSlice < sentence.length - 1) {
            // Stack consists of unmatched open brackets arranged from start to end
            if (stack[0] === sentence[startSlice]) {
                stack.shift();
            } else break;
            startSlice++;
        }

        let endSlice = sentence.length - 1;
        let endIdx = sentence.length - 1;
        while (unmatchedClose.length > 0 && endIdx > startSlice) {
            if (unmatchedClose[unmatchedClose.length - 1] === sentence[endIdx]) {     
                unmatchedClose.pop();
                endSlice = endIdx - 1;
            // sentenceDelimiters used as trailingSentenceDelimiters as it does not have any overlap with brackets
            } else if (!this.sentenceDelimiters.includes(sentence[endIdx])) break;
            endIdx--;
        }
        const sliced = sentence.slice(startSlice, endSlice + 1);
        const slicedLeadingTrim = sliced.length - sliced.trimStart().length;
        selectedOffset = Math.max(0, selectedOffset - startSlice - slicedLeadingTrim);
        return {
            sentence: sliced.trim(),
            sentenceOffset: selectedOffset,
        };
    },

    getSentence(startNode, startOffset) {
        return this.getSentenceContext(startNode, startOffset).sentence;
    },
    
    selectText(x, y, maxLength) {
        if (document.elementFromPoint(x, y)?.closest('a')) {
            return null;
        }
        const hit = this.getCharacterAtPoint(x, y);
        
        if (!hit) {
            this.clearSelection();
            return null;
        }
        
        if (this.selection &&
            hit.node === this.selection.startNode &&
            hit.offset === this.selection.startOffset) {
            this.clearSelection();
            return null;
        }
        
        this.clearSelection();
        
        const container = this.findParagraph(hit.node) || document.body;
        const walker = this.createWalker(container);
        
        let text = '';
        let node = hit.node;
        let offset = hit.offset;
        let ranges = [];
        
        walker.currentNode = node;
        while (text.length < maxLength && node) {
            const content = node.textContent;
            const start = offset;
            
            while (offset < content.length && text.length < maxLength) {
                const char = content[offset];
                if (this.isScanBoundary(char)) {
                    break;
                }
                text += char;
                offset++;
            }
            
            if (offset > start) {
                ranges.push({ node, start, end: offset });
            }
            
            if (offset < content.length || text.length >= maxLength) {
                break;
            }
            
            node = walker.nextNode();
            offset = 0;
        }
        
        if (!text) {
            return null;
        }
        
        this.selection = {
            startNode: hit.node,
            startOffset: hit.offset,
            ranges,
            text
        };
        
        const sentenceContext = this.getSentenceContext(hit.node, hit.offset);
        const normalizedOffset = window.hoshiReader ? this.getNormalizedOffset(hit.node, hit.offset) : null;
        webkit.messageHandlers.textSelected.postMessage({
            text,
            sentence: sentenceContext.sentence,
            rect: this.getSelectionRect(x, y),
            normalizedOffset,
            sentenceOffset: sentenceContext.sentenceOffset
        });
        
        return text;
    },
    
    getSelectionRect(x, y) {
        if (!this.selection?.ranges.length) {
            return null;
        }
        
        const first = this.selection.ranges[0];
        const firstText = first.node.textContent || '';
        const firstChar = firstText.codePointAt(first.start);
        if (firstChar !== undefined) {
            const firstEnd = first.start + String.fromCodePoint(firstChar).length;
            const measured = this.measureBaseCharRect(first.node, first.start, firstEnd);
            if (measured) {
                return {
                    x: measured.left,
                    y: measured.top,
                    width: measured.width,
                    height: measured.height
                };
            }
        }

        const range = document.createRange();
        range.setStart(first.node, first.start);
        range.setEnd(first.node, first.start + 1);
        
        const rects = Array.from(range.getClientRects());
        const rect = rects.find(rect => x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) ?? range.getBoundingClientRect();
        return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
    },
    
    highlightSelection(charCount) {
        if (!this.selection?.ranges.length) {
            return;
        }

        this.clearHighlightLayer();
        const boxes = this.collectHighlightBoxes(charCount);
        if (!boxes.length) {
            return;
        }

        const layer = this.ensureHighlightLayer();
        for (const rect of boxes) {
            const box = document.createElement('div');
            box.className = 'hoshi-selection-box';
            box.style.left = `${rect.left + window.scrollX}px`;
            box.style.top = `${rect.top + window.scrollY}px`;
            box.style.width = `${rect.width}px`;
            const bottomTrim = Math.max(2, Math.round(rect.height * 0.12));
            box.style.height = `${Math.max(1, rect.height - bottomTrim)}px`;
            layer.appendChild(box);
        }
    },

    collectHighlightBoxes(charCount) {
        const rawRects = [];
        let remaining = charCount;
        for (const r of this.selection.ranges) {
            if (remaining <= 0) {
                break;
            }
            
            for (let offset = r.start; offset < r.end && remaining > 0;) {
                const char = String.fromCodePoint(r.node.textContent.codePointAt(offset));
                const nextOffset = offset + char.length;
                const rect = this.measureBaseCharRect(r.node, offset, nextOffset);
                if (rect) {
                    rawRects.push(rect);
                }
                offset = nextOffset;
                remaining--;
            }
        }
        return this.mergeHighlightRects(rawRects);
    },

    measureBaseCharRect(node, start, end) {
        if (this.isFurigana(node)) {
            return null;
        }
        const rubyRect = this.measureRubyBaseCharRect(node, start, end);
        if (rubyRect) {
            return rubyRect;
        }
        const range = document.createRange();
        range.setStart(node, start);
        range.setEnd(node, end);
        const rects = Array.from(range.getClientRects()).filter(rect => rect.width > 0 && rect.height > 0);
        if (!rects.length) {
            return null;
        }
        let rect = rects[0];
        for (const candidate of rects.slice(1)) {
            if (candidate.width * candidate.height > rect.width * rect.height) {
                rect = candidate;
            }
        }

        const element = node.parentElement || document.body;
        const style = window.getComputedStyle(element);
        const fontSize = Number.parseFloat(style.fontSize) || rect.height;
        const padding = Math.max(1, fontSize * 0.06);

        if (this.isVertical()) {
            const width = Math.min(rect.width, fontSize * 1.08);
            return {
                left: rect.left + (rect.width - width) / 2 - padding,
                top: rect.top - padding,
                right: rect.left + (rect.width + width) / 2 + padding,
                bottom: rect.bottom + padding,
                width: width + padding * 2,
                height: rect.height + padding * 2,
                axisCenter: rect.left + rect.width / 2,
                fontSize,
            };
        }

        const height = Math.min(rect.height, fontSize * 1.08);
        return {
            left: rect.left - padding,
            top: rect.bottom - height - padding,
            right: rect.right + padding,
            bottom: rect.bottom + padding,
            width: rect.width + padding * 2,
            height: height + padding * 2,
            axisCenter: rect.top + rect.height / 2,
            fontSize,
        };
    },

    measureRubyBaseCharRect(node, start, end) {
        const element = node.parentElement;
        const ruby = element?.closest('ruby');
        if (!element || !ruby || element.closest('rt, rp')) {
            return null;
        }

        const rubyRect = ruby.getBoundingClientRect();
        if (rubyRect.width <= 0 || rubyRect.height <= 0) {
            return null;
        }

        const text = node.textContent || '';
        const chars = Array.from(text);
        if (!chars.length) {
            return null;
        }

        let charIndex = 0;
        for (let offset = 0; offset < start && charIndex < chars.length; charIndex++) {
            offset += chars[charIndex].length;
        }

        const style = window.getComputedStyle(element);
        const fontSize = Number.parseFloat(style.fontSize) || (this.isVertical() ? rubyRect.width : rubyRect.height);
        const padding = Math.max(1, fontSize * 0.06);
        const prefixText = chars.slice(0, charIndex).join('');
        const charText = chars[charIndex] || text.slice(start, end);
        const totalAdvance = Math.max(1, this.measureTextAdvance(text, style, fontSize));
        const prefixAdvance = this.measureTextAdvance(prefixText, style, fontSize);
        const charAdvance = Math.max(1, this.measureTextAdvance(charText, style, fontSize));

        if (this.isVertical()) {
            const top = rubyRect.top + (rubyRect.height - totalAdvance) / 2 + prefixAdvance;
            const width = Math.min(fontSize * 1.08, rubyRect.width);
            return {
                left: rubyRect.left + (rubyRect.width - width) / 2 - padding,
                top: top - padding,
                right: rubyRect.left + (rubyRect.width + width) / 2 + padding,
                bottom: top + charAdvance + padding,
                width: width + padding * 2,
                height: charAdvance + padding * 2,
                axisCenter: rubyRect.left + rubyRect.width / 2,
                fontSize,
            };
        }

        const left = rubyRect.left + (rubyRect.width - totalAdvance) / 2 + prefixAdvance;
        const width = Math.min(charAdvance, fontSize * 1.02);
        const height = Math.min(rubyRect.height, fontSize * 1.08);
        return {
            left: left + (charAdvance - width) / 2 - padding,
            top: rubyRect.bottom - height - padding,
            right: left + (charAdvance + width) / 2 + padding,
            bottom: rubyRect.bottom + padding,
            width: width + padding * 2,
            height: height + padding * 2,
            axisCenter: rubyRect.top + rubyRect.height / 2,
            fontSize,
        };
    },

    measureTextAdvance(text, style, fallback) {
        if (!text) {
            return 0;
        }
        const canvas = this.measureCanvas || (this.measureCanvas = document.createElement('canvas'));
        const context = canvas.getContext('2d');
        if (!context) {
            return Array.from(text).length * fallback;
        }
        context.font = [
            style.fontStyle || 'normal',
            style.fontVariant || 'normal',
            style.fontWeight || '400',
            style.fontSize || `${fallback}px`,
            style.fontFamily || 'sans-serif'
        ].join(' ');
        const measured = context.measureText(text).width;
        return Number.isFinite(measured) && measured > 0
            ? measured
            : Array.from(text).length * fallback;
    },

    mergeHighlightRects(rects) {
        if (!rects.length) {
            return [];
        }
        const vertical = this.isVertical();
        const sorted = rects.slice().sort((a, b) => {
            if (vertical) {
                return Math.abs(a.axisCenter - b.axisCenter) > 2 ? a.axisCenter - b.axisCenter : a.top - b.top;
            }
            return Math.abs(a.axisCenter - b.axisCenter) > 2 ? a.axisCenter - b.axisCenter : a.left - b.left;
        });

        const merged = [];
        for (const rect of sorted) {
            const last = merged[merged.length - 1];
            const threshold = Math.max(3, Math.min(rect.fontSize, last?.fontSize || rect.fontSize) * 0.72);
            if (last && Math.abs(last.axisCenter - rect.axisCenter) <= threshold) {
                last.left = Math.min(last.left, rect.left);
                last.top = Math.min(last.top, rect.top);
                last.right = Math.max(last.right, rect.right);
                last.bottom = Math.max(last.bottom, rect.bottom);
                last.width = last.right - last.left;
                last.height = last.bottom - last.top;
                last.axisCenter = vertical
                    ? (last.left + last.right) / 2
                    : (last.top + last.bottom) / 2;
                last.fontSize = Math.max(last.fontSize, rect.fontSize);
            } else {
                merged.push({ ...rect });
            }
        }
        return merged.map(rect => ({
            left: rect.left,
            top: rect.top,
            width: rect.width,
            height: rect.height,
        }));
    },

    ensureHighlightLayer() {
        if (this.highlightLayer?.isConnected) {
            return this.highlightLayer;
        }
        const layer = document.createElement('div');
        layer.className = 'hoshi-selection-layer';
        document.body.appendChild(layer);
        this.highlightLayer = layer;
        return layer;
    },

    clearHighlightLayer() {
        this.highlightLayer?.remove();
        this.highlightLayer = null;
    },
    
    getNormalizedOffset(targetNode, offset) {
        let count = window.hoshiReader.nodeStartOffsets.get(targetNode) ?? 0;
        const text = targetNode.textContent;
        for (let i = 0; i < offset;) {
            const char = String.fromCodePoint(text.codePointAt(i));
            if (window.hoshiReader.isMatchableChar(char)) {
                count++;
            }
            i += char.length;
        }
        return count;
    },
    
    clearSelection() {
        window.getSelection()?.removeAllRanges();
        CSS.highlights?.delete('hoshi-selection');
        this.clearHighlightLayer();
        this.selection = null;
    }
};

let lastHasSelection = false;
document.addEventListener('selectionchange', () => {
    const s = getSelection();
    const hasSelection = !!s && !s.isCollapsed;
    if (hasSelection === lastHasSelection) return;
    lastHasSelection = hasSelection;
    try { window.webkit?.messageHandlers?.selectionState?.postMessage(hasSelection); } catch {}
});
