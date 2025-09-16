#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Pure-Python extractor for EAD PDFs (no external binaries).
Uses pdfplumber + header-bounded parsing for robust fields.

CLI:
  python3 extract.py <PDF file or folder>

Output: JSON array with:
  dataDeclaratie, nrMrn, identificare, numeExportator, buc, greutate, descriereaMarfurilor, file
"""

import sys, re, json, unicodedata
from pathlib import Path
from typing import List, Dict, Optional, Tuple

import pdfplumber

import warnings, logging
warnings.filterwarnings("ignore")
for name in ("pdfminer", "pdfminer.pdfinterp", "pdfminer.pdfpage", "pdfminer.psparser"):
    logging.getLogger(name).setLevel(logging.ERROR)

# ------------------------ utilities ------------------------

def strip_accents(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")

def norm(s: str) -> str:
    return strip_accents(s or "").lower().strip()

def words_and_texts(path: str) -> Tuple[List[dict], List[str]]:
    """Return all word boxes across pages AND page texts (1 string per page)."""
    words: List[dict] = []
    page_texts: List[str] = []
    with pdfplumber.open(path) as pdf:
        for page in pdf.pages:
            page_texts.append(page.extract_text() or "")
            ws = page.extract_words(
                use_text_flow=True,
                keep_blank_chars=False,
                x_tolerance=1.0,
                y_tolerance=2.0,
            ) or []
            for w in ws:
                w["page"] = page.page_number
            words.extend(ws)
    return words, page_texts

def build_lines(words: List[dict], y_tol: float = 2.5) -> List[dict]:
    """Group words into line bands per page; keep text and normalized text."""
    lines: List[dict] = []
    for page in sorted({w["page"] for w in words}):
        ws = [w for w in words if w["page"] == page]
        ws.sort(key=lambda x: (x["top"], x["x0"]))
        for w in ws:
            placed = False
            for ln in lines:
                if ln["page"] != page:
                    continue
                if not (w["bottom"] < ln["top"] - y_tol or w["top"] > ln["bottom"] + y_tol):
                    ln["words"].append(w)
                    ln["top"] = min(ln["top"], w["top"])
                    ln["bottom"] = max(ln["bottom"], w["bottom"])
                    placed = True
                    break
            if not placed:
                lines.append({"page": page, "top": w["top"], "bottom": w["bottom"], "words": [w]})
    for ln in lines:
        ln["words"].sort(key=lambda x: x["x0"])
        ln["text"] = " ".join(w["text"] for w in ln["words"])
        ln["norm"] = norm(ln["text"])
        ln["page"] = ln["words"][0]["page"]
    lines.sort(key=lambda ln: (ln["page"], ln["top"]))
    return lines

def same_line(words: List[dict], page: int, top: float, bottom: float, tol: float = 2.5) -> List[dict]:
    return [w for w in words if w["page"] == page and not (w["bottom"] < top - tol or w["top"] > bottom + tol)]

def slice_between(txt: str, start_pat: str, end_pats: List[str]) -> Optional[str]:
    """Return substring of txt after start_pat and before the earliest of end_pats."""
    m = re.search(start_pat, txt, flags=re.I)
    if not m:
        return None
    start = m.end()
    ends = []
    for p in end_pats:
        for mm in re.finditer(p, txt, flags=re.I):
            if mm.start() > start:
                ends.append(mm.start())
    end = min(ends) if ends else len(txt)
    return txt[start:end]


# ------------------------ generic fields (kept stable) ------------------------

def extract_exporter(words: List[dict]) -> Optional[str]:
    """First uppercase-ish line below 'Exportator [13 01]'. Skip 'Nr:' VAT line; strip leading checkbox (X/☒/✓/...)."""
    CHECK_TICK = re.compile(r"^[Xx☒✓✔✘]$")
    for w in words:
        if norm(w["text"]).startswith("exportator"):
            page, bottom = w["page"], w["bottom"]
            nxt = [ww for ww in words if ww["page"] == page and bottom < ww["top"] <= bottom + 60]
            nxt.sort(key=lambda x: (x["top"], x["x0"]))
            # group into lines
            line_groups: List[dict] = []
            for ww in nxt:
                if not line_groups or abs(line_groups[-1]["top"] - ww["top"]) > 2.5:
                    line_groups.append({"top": ww["top"], "words": [ww]})
                else:
                    line_groups[-1]["words"].append(ww)
            for ln in line_groups:
                tokens = ln["words"][:]
                raw = " ".join(t["text"] for t in tokens).strip()
                if norm(raw).startswith("nr"):
                    continue
                while tokens and CHECK_TICK.match(tokens[0]["text"]):
                    tokens.pop(0)
                cleaned = []
                for t in tokens:
                    if re.fullmatch(r"\[?\d{1,2}\]?|\[|\]", t["text"]):
                        continue
                    cleaned.append(t["text"])
                name = " ".join(cleaned).strip()
                if name:
                    return name
    return None

def extract_greutate(words: List[dict]) -> Optional[int]:
    """Greutate (gross mass) just below 'Masa ... brută [18 04]'; take integer part."""
    num_pat = re.compile(r"^(?:\d{1,3}(?:[.,]\d{3})*|\d+(?:[.,]\d+)?)$")
    for w in words:
        if norm(w["text"]) == "masa":
            band = same_line(words, w["page"], w["top"], w["bottom"])
            if not any("brut" in norm(ww["text"]) for ww in band):
                continue
            x0, x1 = w["x0"] - 20, w["x1"] + 140
            candidates = [
                ww for ww in words
                if ww["page"] == w["page"]
                   and w["bottom"] <= ww["top"] <= w["bottom"] + 24
                   and x0 <= ww["x0"] <= x1
                   and num_pat.match(ww["text"])
            ]
            if candidates:
                candidates.sort(key=lambda ww: (abs(ww["top"] - w["bottom"]), ww["x0"]))
                val = candidates[0]["text"]
                if "," in val or "." in val:
                    val = re.split(r"[.,]", val)[0]
                try:
                    return int(val.replace(" ", "").replace("\u00A0", "").replace(".", ""))
                except ValueError:
                    pass
    return None

def extract_mrn(words: List[dict], filename: Optional[str] = None) -> Optional[str]:
    """Find token starting with '25RO...' and return substring AFTER the 11th char (no C/N assumption)."""
    def mrn_from_token(tok: str) -> Optional[str]:
        s = re.sub(r"[^A-Z0-9]", "", tok.upper())
        if s.startswith("25RO") and len(s) >= 18:
            return s[11:]
        return None
    for w in words:
        m = mrn_from_token(w["text"])
        if m:
            return m
    joined = re.sub(r"\s+", "", "".join(w["text"] for w in words))
    m2 = mrn_from_token(joined)
    if m2:
        return m2
    if filename:
        base = Path(filename).stem
        m3 = mrn_from_token(base)
        if m3:
            return m3
    return None

def extract_data_declaratie(words: List[dict]) -> Optional[str]:
    """Find first date; return dd-mm."""
    pat = re.compile(r"\b(\d{1,2})[./-](\d{1,2})(?:[./-]\d{2,4})?\b")
    for w in words:
        if "data" in norm(w["text"]):
            band = same_line(words, w["page"], w["top"], w["bottom"])
            m = pat.search(" ".join(x["text"] for x in band))
            if m:
                return f"{m.group(1).zfill(2)}-{m.group(2).zfill(2)}"
    for w in words:
        m = pat.search(w["text"])
        if m:
            return f"{m.group(1).zfill(2)}-{m.group(2).zfill(2)}"
    return None

def extract_identificare_from_pages(page_texts: List[str]) -> Optional[str]:
    """
    AWB/CMR inside:
      'Documentul de transport [12 05]' ... 'Documentul precedent [12 01]'
    If contains N740/N741 -> AWB; if N730 -> CMR.
    """
    for txt in page_texts:
        t = strip_accents(txt)
        hdr_iter = list(re.finditer(r"Documentul de transport\s*\[\s*12\s*05\s*\]", t, flags=re.I))
        if not hdr_iter:
            continue
        end_iter = list(re.finditer(r"Documentul precedent\s*\[\s*12\s*01\s*\]", t, flags=re.I))
        ends = [m.start() for m in end_iter]
        for m in hdr_iter:
            end_pos = min((e for e in ends if e > m.end()), default=len(t))
            seg = t[m.end():end_pos]
            if re.search(r"\bN\s*740\b|\bN\s*741\b", seg, flags=re.I):
                return "AWB"
            if re.search(r"\bN\s*730\b", seg, flags=re.I):
                return "CMR"
    return None


# ------------------------ the two fixed fields ------------------------

def extract_buc_from_pages(page_texts: List[str]) -> Optional[int]:
    """
    Pieces count from *inside the packages section only*:
      between 'Tipul și nr. de colete, mărcile de expediție [18 06]'
      and the next major header (Descrierea / Cod nomenclatură / Valoarea / Masa / Regim).
    Then match 'PC / 92 / ...' or 'PX / 2 / ...' → return the middle number.
    """
    next_headers = [
        r"Descrierea\s+m[ăa]rfurilor",
        r"Cod\s+nomenclatur[ăa]\s+combinat[ăa]",
        r"\bValoarea\b",
        r"\bMasa\b",
        r"\bRegim",
    ]
    heads = r"(?:PC|PX|COLI|COL|CT|BX|PAL(?:ETI|ET)?|PCE|PCS)"
    # accept either ' / ' or just whitespace between head and number
    value_pat = re.compile(rf"(?m)^[^\S\r\n]*{heads}\s*(?:/|\s)\s*(\d{{1,6}})\b")

    for txt in page_texts:
        t = strip_accents(txt)
        seg = slice_between(
            t,
            r"Tipul\s+si\s+nr\.?\s+de\s+colete.*?\[\s*18\s*06\s*\]|\bTipul\s+si\b.*colete",
            next_headers,
        )
        if not seg:
            continue
        # try 'head / N /' form first
        m = re.search(rf"{heads}\s*/\s*(\d{{1,6}})\s*/", seg, flags=re.I)
        if not m:
            # fall back to line-start tolerant variant
            m = value_pat.search(seg)
        if m:
            v = int(m.group(1))
            if 1 <= v <= 1000:
                return v
    return None

def extract_descriere_from_pages(page_texts: List[str]) -> Optional[str]:
    """
    Description from *inside the description section only*:
      between 'Descrierea mărfurilor [18 05]' and the next major header.
    Take the first non-empty, bracket-free line, strip leading index like '1'/'1.'/'1)'.
    """
    next_headers = [
        r"Cod\s+nomenclatur[ăa]\s+combinat[ăa]",
        r"\bValoarea\b",
        r"\bMasa\b",
        r"Tipul\s+si\s+nr\.?\s+de\s+colete",
        r"\bRegim",
    ]
    for txt in page_texts:
        # keep original spacing for nicer output, but use accent-stripped for slicing
        t = strip_accents(txt)
        seg = slice_between(
            t,
            r"Descrierea\s+m[ăa]rfurilor\s*\[\s*18\s*05\s*\]|\bDescrierea\s+m[ăa]rfurilor\b",
            next_headers,
        )
        if not seg:
            continue
        # Work line by line from the original (non-stripped) text,
        # but align by splitting the same way.
        lines = [ln.rstrip() for ln in txt.splitlines()]
        # Find the header line index in the original text
        header_idx = None
        for i, ln in enumerate(lines):
            if re.search(r"Descrierea\s+m[ăa]rfurilor", ln, flags=re.I):
                header_idx = i
                break
        if header_idx is None:
            continue
        # Pick the first clean non-empty line after the header
        for j in range(header_idx + 1, min(header_idx + 8, len(lines))):
            candidate = lines[j].strip()
            if not candidate:
                continue
            if "[" in candidate or "]" in candidate:
                # skip lines that still show field-code brackets
                continue
            # skip obvious other headers that sometimes appear right below
            bad_starts = ("Expeditor", "Destinatar", "Alt", "Ţara", "Tara", "Cod ", "Unită", "Tipul", "Regim", "Valoarea", "Masa", "Totalul")
            if candidate.startswith(bad_starts):
                continue
            # Strip leading index "1", "1.", "1)"
            candidate = re.sub(r"^\s*\d+[.)]?\s+", "", candidate)
            # Normalize hyphen spacing & whitespace
            candidate = re.sub(r"\s*-\s*", " - ", candidate)
            candidate = re.sub(r"\s+([.,;:])", r"\1", candidate)
            candidate = re.sub(r"\s{2,}", " ", candidate).strip()
            if candidate:
                return candidate
    return None


# ------------------------ per-PDF + CLI ------------------------

def extract_one_pdf(pdf_path: Path) -> Dict[str, Optional[str]]:
    words, page_texts = words_and_texts(str(pdf_path))

    res = {
        "dataDeclaratie": extract_data_declaratie(words),
        "nrMrn": extract_mrn(words, pdf_path.name),
        "identificare": extract_identificare_from_pages(page_texts),
        "numeExportator": extract_exporter(words),
        "buc": extract_buc_from_pages(page_texts),
        "greutate": extract_greutate(words),
        "descriereaMarfurilor": extract_descriere_from_pages(page_texts),
        "file": pdf_path.name,
    }
    return res

def find_pdfs(root: Path) -> List[Path]:
    root = Path(root)
    if root.is_file() and root.suffix.lower() == ".pdf":
        return [root]
    return sorted(root.rglob("*.pdf"))

def main():
    if len(sys.argv) < 2:
        print("Usage: extract.py <PDF file or folder>", file=sys.stderr)
        sys.exit(2)
    root = Path(sys.argv[1])
    if not root.exists():
        print(f"Path not found: {root}", file=sys.stderr)
        sys.exit(2)
    results = [extract_one_pdf(p) for p in find_pdfs(root)]
    print(json.dumps(results, ensure_ascii=False))

if __name__ == "__main__":
    main()
