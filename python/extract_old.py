#!/usr/bin/env python3
import logging

logging.getLogger("pdfminer").setLevel(logging.ERROR)
logging.getLogger("pdfminer.pdfpage").setLevel(logging.ERROR)

import sys, json
from pathlib import Path
from decimal import Decimal, InvalidOperation
import pdfplumber
import re
import unicodedata

MRN_RE = re.compile(r"\b(25RO[A-Z0-9]{6,})\b")  # strict: must start with 25RO

def extract_identificare(full_text):
    """
    Multi-block aware parser:
      Scans every 'Documentul de transport [12 05]' block until the next
      'Documentul precedent [12 01]' and returns:
        - 'AWB' if the block contains N740 or N741
        - 'CMR' if the block contains N730
      If multiple blocks exist, the first with a match decides.
    """
    import re
    text = full_text or ""
    hdr_iter = list(re.finditer(r"Documentul de transport\s*\[\s*12\s*05\s*\]", text, flags=re.I))
    if not hdr_iter:
        return None
    end_iter = list(re.finditer(r"Documentul precedent\s*\[\s*12\s*01\s*\]", text, flags=re.I))
    end_positions = [m.start() for m in end_iter]

    for m in hdr_iter:
        end_candidates = [e for e in end_positions if e > m.end()]
        end_pos = min(end_candidates) if end_candidates else len(text)
        seg = text[m.end(): end_pos]
        has_n730 = re.search(r"\bN\s*730\b", seg, flags=re.I) is not None
        has_n740 = re.search(r"\bN\s*740\b", seg, flags=re.I) is not None
        has_n741 = re.search(r"\bN\s*741\b", seg, flags=re.I) is not None
        if has_n740 or has_n741:
            return "AWB"
        if has_n730:
            return "CMR"
    return None

def extract_buc(lines):
    """
    Read the '[18 06]' block; for the next few non-empty lines,
    look for '/ (PC|PX) / <number> / ...' and return that <number>.
    Fallback: if there are many slashes, take the last three meaningful
    slash-separated groups and return the middle if it is all digits.
    """
    for i, l in enumerate(lines):
        if re.search(r"\[\s*18\s*06\s*\]", l):
            for j in range(i + 1, min(i + 6, len(lines))):
                s = lines[j].strip()
                if not s:
                    continue
                # normalize whitespace
                s2 = re.sub(r"\s+", " ", s)
                # Preferred pattern: ... / (PC|PX) / <digits> / ...
                m = re.search(r"/\s*(?:PC|PX)\s*/\s*(\d+)\s*/", s2, flags=re.I)
                if m:
                    return int(m.group(1))
                # Fallback: take last three non-empty slash groups
                parts = [p.strip() for p in s2.split("/") if p.strip()]
                if len(parts) >= 3 and re.fullmatch(r"\d+", parts[-2]):
                    return int(parts[-2])
            break
    return None


def find_mrn(lines):
    """
    Find the MRN value around the 'MRN' label.
    Then return only the part starting from the 11th character (index 10).
    """
    for i, line in enumerate(lines):
        if re.search(r"\bMRN\b", line, re.IGNORECASE):
            start = max(0, i - 3)
            end   = min(len(lines), i + 8)
            window = lines[start:end]

            # 1) Try line-by-line
            for w in window:
                m = MRN_RE.search(w)
                if m:
                    mrn = m.group(1)
                    return mrn[11:] if len(mrn) > 11 else mrn

            # 2) Fallback: join window
            joined = " ".join(w.strip() for w in window if w.strip())
            m = MRN_RE.search(joined)
            if m:
                mrn = m.group(1)
                return mrn[11:] if len(mrn) > 11 else mrn

            return None

    # 3) Last resort: scan whole doc
    joined_all = " ".join(l.strip() for l in lines if l.strip())
    m = MRN_RE.search(joined_all)
    if m:
        mrn = m.group(1)
        return mrn[10:] if len(mrn) > 10 else mrn
    return None

def extract_greutate(lines):
    """
    Find 'Masa brută [18 04]' and read the value directly under it.
    - Ignores digits glued to letters (e.g. ROBU1030)
    - Prefers numbers like 230.000 / 989,000 or plain integers
    - Returns integer part only
    """
    hdr_pat = re.compile(r"Masa\s+brut[ăa]\s*\[\s*18\s*04\s*\]", re.I)
    num_pat = re.compile(
        r"(?<![A-Za-z0-9])"           # not glued to a letter/number before
        r"(\d{2,}(?:[.,]\d{3})*|\d{2,}(?:[.,]\d+)?)"  # 230.000 / 989,000 / 230 / 230.5
        r"(?![A-Za-z0-9])"            # not glued to a letter/number after
    )

    for i, l in enumerate(lines):
        if hdr_pat.search(l):
            # Scan next 1–3 non-empty lines (usually the value is right below or on next row)
            lookahead_limit = min(i + 4, len(lines))
            for j in range(i + 1, lookahead_limit):
                cand = lines[j].strip()
                if not cand or "[" in cand:   # skip headers / empty
                    continue
                # normalize spaces
                cand_norm = re.sub(r"\s+", " ", cand)
                # find all numeric candidates not attached to letters
                nums = [m.group(1) for m in num_pat.finditer(cand_norm)]
                if not nums:
                    continue
                # Heuristic: the first candidate on this line is gross weight; take integer part
                raw = nums[0]
                int_part = re.split(r"[.,]", raw)[0]
                try:
                    return int(int_part)
                except ValueError:
                    continue
            break
    return None

def _strip_accents(s):
    if s is None:
        return ""
    return ''.join(c for c in unicodedata.normalize('NFD', s) if unicodedata.category(c) != 'Mn')

def _search_any(page, patterns):
    """Return a list of bboxes for the first pattern that matches something on this page."""
    for pat in patterns:
        boxes = page.search(pat, regex=True) or []
        if boxes:
            return boxes
    return []

def extract_descrierea_marfurilor_bbox(pdf_path):
    """
    Read ONLY the value inside the 'Descrierea mărfurilor [18 05]' cell.
    - Determine left bound from the 'Descrierea mărfurilor' header bbox
    - Determine right bound from the next header ('Tipul și nr. de colete' or '[18 06]')
    - Take the first data line below the header within [left, right)
    """
    # robust patterns (with/without diacritics)
    desc_hdr_patterns = [
        r"Descrierea\s+m[ăa]rfurilor\s*\[\s*18\s*0?5\s*\]",
        r"Descrierea\s+m[ăa]rfurilor"  # fallback if [18 05] not on same line
    ]
    next_hdr_patterns = [
        r"Tipul\s+și\s+nr\.\s+de\s+colete",                       # diacritics
        r"Tipul\s+si\s+nr\.\s+de\s+colete",                       # no diacritics
        r"\[\s*18\s*0?6\s*\]",                                    # [18 06]
        r"Cod\s+CUS\s*\[\s*18\s*0?8\s*\]",                        # [18 08]
        r"Cod\s+ONU\s*\[\s*18\s*0?7\s*\]"                         # [18 07]
    ]

    with pdfplumber.open(pdf_path) as pdf:
        for page in pdf.pages:
            # 1) Find the description header bbox (use the *leftmost* one)
            desc_boxes = _search_any(page, desc_hdr_patterns)
            if not desc_boxes:
                continue
            desc_box = min(desc_boxes, key=lambda b: (b["top"], b["x0"]))
            x_left = desc_box["x0"]
            y_header_bottom = desc_box["bottom"]

            # 2) Find the next header to the right on the same header row to set x_right
            #    We pick the *nearest* header with x0 > x_left and top close to desc_box.top (same row)
            candidates = _search_any(page, next_hdr_patterns)
            x_right = None
            if candidates:
                # keep headers that are to the right and roughly on the same y-band
                same_row = [
                    b for b in candidates
                    if b["x0"] > x_left and abs(b["top"] - desc_box["top"]) < 15
                ]
                if same_row:
                    x_right = min(same_row, key=lambda b: b["x0"])["x0"]
            # fallback: if we didn't find a neighbor header, set a wide right bound = page width
            if x_right is None:
                x_right = page.width

            # 3) Pull words and find the first data line just under the header
            words = page.extract_words(
                keep_blank_chars=False,
                use_text_flow=True,
                x_tolerance=1,
                y_tolerance=3,
            )
            if not words:
                continue

            # words below header
            below = [w for w in words if w["top"] > y_header_bottom]
            if not below:
                continue
            below.sort(key=lambda w: w["top"])
            first_line_top = below[0]["top"]

            # build a small vertical band around that line
            band_top = first_line_top - 2
            band_bot = first_line_top + 8

            # 4) Keep only words inside the description cell bounds [x_left, x_right)
            cell_words = [
                w for w in below
                if band_top <= w["top"] <= band_bot and (w["x0"] >= x_left - 0.5) and (w["x0"] < x_right - 0.5)
            ]
            cell_words.sort(key=lambda w: w["x0"])

            text = " ".join(w["text"].strip() for w in cell_words).strip()
            if text:
                # clean accidental header residues if any slipped in
                text = re.sub(r"\s*(Tipul\s+și\s+nr\.\s+de\s+colete|Tipul\s+si\s+nr\.\s+de\s+colete).*", "", text, flags=re.I)
                text = re.sub(r"\s*\[\s*18\s*0?6\s*\].*", "", text)   # cut if [18 06] leaked
                text = re.sub(r"\s*Cod\s+CUS.*", "", text, flags=re.I)
                text = re.sub(r"\s*Cod\s+ONU.*", "", text, flags=re.I)
                return text.strip()

    return ""

def text_from_pdf(path):
    chunks = []
    with pdfplumber.open(path) as pdf:
        for p in pdf.pages:
            t = p.extract_text() or ""
            chunks.append(t)
    return "\n".join(chunks)

def extract_fields(text):
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    data = {}

    # 1. data acceptarii
    for l in lines:
        if "[15 09]" in l:
            # Search for date in format dd-mm-YYYY
            m = re.search(r"(\d{2}-\d{2})-\d{4}", l)
            if m:
                data["dataDeclaratie"] = m.group(1)  # e.g. "02-09"
            break


    # 2. mrn
    mrn = find_mrn(lines)
    if mrn:
        data["nrMrn"] = mrn

     # 3. identificare (NEW)
    data["identificare"] = extract_identificare(text)

    # 4. numeExportator
    for i, l in enumerate(lines):
        if "Exportator [13 01]" in l:
            if i + 1 < len(lines):  # make sure there's a next line
                next_line = lines[i + 1].strip()
                data["numeExportator"] = next_line
            break



    # 5. buc (PX / <buc> / FARA MARCA)
    buc = extract_buc(lines)
    if buc is not None:
        data["buc"] = buc

    # 6. greutate (Masa brută [18 04])
    greutate = extract_greutate(lines)
    if greutate is not None:
        data["greutate"] = greutate


    return data

def main(folder_path):
    results = []
    for pdf_path in Path(folder_path).glob("*.pdf"):
        try:
            with pdfplumber.open(pdf_path) as pdf:
                text = "\n".join(page.extract_text() or "" for page in pdf.pages)
            rec = extract_fields(text)
            rec["file"] = pdf_path.name

            desc = extract_descrierea_marfurilor_bbox(str(pdf_path))
            if desc:
                rec["descriereaMarfurilor"] = desc

        except Exception as e:
            rec = {"file": pdf_path.name, "error": str(e)}
        results.append(rec)

    # emit JSON array to stdout
    print(json.dumps(results, ensure_ascii=False))

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: extract_old.py <folder>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])

