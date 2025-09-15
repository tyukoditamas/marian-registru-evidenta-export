#!/usr/bin/env python3
"""
Reworked extraction script for "Registru Evidenta" PDFs.

This version avoids the need for third‑party Python PDF libraries by using the
`pdftotext` command‑line tool to extract textual content from each PDF.  It
parses the resulting text to pull out the fields of interest:
- dataDeclaratie – date of acceptance (dd‑mm) from the [15 09] line.
- nrMrn – MRN value.
- identificare – "AWB" or "CMR".
- numeExportator – the exporter’s name.
- buc – number of pieces from the [18 06] block.
- greutate – gross weight from the "Masa brută [18 04]" block.
- descriereaMarfurilor – description from the "Descrierea mărfurilor [18 05]" cell,
  stripped of leading article numbers and trailing headers.

Run as: `python3 extract.py <folder>`, where <folder> contains PDF files.
"""

import logging
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import List, Optional
import unicodedata

logging.getLogger("pdfminer").setLevel(logging.ERROR)
logging.getLogger("pdfminer.pdfpage").setLevel(logging.ERROR)

# Regular expression for MRN (25RO + 6+ alphanumerics)
MRN_RE = re.compile(r"\b(25RO[A-Z0-9]{6,})\b")

def pdf_to_text(path: str) -> str:
    """Return the text from a PDF using pdftotext -layout."""
    try:
        result = subprocess.run(
            ["pdftotext", "-layout", path, "-"],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return result.stdout
    except Exception as exc:
        logging.error("pdftotext failed on %s: %s", path, exc)
        return ""

def extract_identificare(full_text: str) -> Optional[str]:
    """Return AWB/CMR by scanning Documentul de transport blocks."""
    hdr_iter = list(re.finditer(r"Documentul de transport\s*\[\s*12\s*05\s*\]", full_text, flags=re.I))
    if not hdr_iter:
        return None
    end_iter = list(re.finditer(r"Documentul precedent\s*\[\s*12\s*01\s*\]", full_text, flags=re.I))
    end_positions = [m.start() for m in end_iter]
    for m in hdr_iter:
        end_candidates = [e for e in end_positions if e > m.end()]
        end_pos = min(end_candidates) if end_candidates else len(full_text)
        seg = full_text[m.end():end_pos]
        if re.search(r"\bN\s*740\b|\bN\s*741\b", seg, flags=re.I):
            return "AWB"
        if re.search(r"\bN\s*730\b", seg, flags=re.I):
            return "CMR"
    return None

def extract_buc(lines: List[str]) -> Optional[int]:
    """Parse the [18 06] block to extract the number of pieces (buc)."""
    for i, l in enumerate(lines):
        if re.search(r"\[\s*18\s*06\s*\]", l):
            for j in range(i + 1, min(i + 6, len(lines))):
                s = lines[j].strip()
                if not s:
                    continue
                s2 = re.sub(r"\s+", " ", s)
                m = re.search(r"/\s*(?:PC|PX)\s*/\s*(\d+)\s*/", s2, flags=re.I)
                if m:
                    return int(m.group(1))
                parts = [p.strip() for p in s2.split("/") if p.strip()]
                if len(parts) >= 3 and re.fullmatch(r"\d+", parts[-2]):
                    return int(parts[-2])
            break
    return None

def find_mrn(lines: List[str]) -> Optional[str]:
    """Find and normalise the MRN around the 'MRN' label."""
    for i, line in enumerate(lines):
        if re.search(r"\bMRN\b", line, re.I):
            start = max(0, i - 3)
            end = min(len(lines), i + 8)
            window = lines[start:end]
            for w in window:
                m = MRN_RE.search(w)
                if m:
                    mrn = m.group(1)
                    return mrn[11:] if len(mrn) > 11 else mrn
            joined = " ".join(w.strip() for w in window if w.strip())
            m = MRN_RE.search(joined)
            if m:
                mrn = m.group(1)
                return mrn[11:] if len(mrn) > 11 else mrn
            return None
    joined_all = " ".join(l.strip() for l in lines if l.strip())
    m = MRN_RE.search(joined_all)
    if m:
        mrn = m.group(1)
        return mrn[10:] if len(mrn) > 10 else mrn
    return None

def extract_greutate(lines: List[str]) -> Optional[int]:
    """Extract the gross weight ('Masa brută') as an integer."""
    hdr_pat = re.compile(r"Masa\s+brut[ăa]\s*\[\s*18\s*04\s*\]", re.I)
    num_pat = re.compile(r"(?<![A-Za-z0-9])(\d{2,}(?:[.,]\d{3})*|\d{2,}(?:[.,]\d+)?)(?![A-Za-z0-9])")
    for i, l in enumerate(lines):
        if hdr_pat.search(l):
            for j in range(i + 1, min(i + 4, len(lines))):
                cand = lines[j].strip()
                if not cand or "[" in cand:
                    continue
                cand_norm = re.sub(r"\s+", " ", cand)
                nums = [m.group(1) for m in num_pat.finditer(cand_norm)]
                if not nums:
                    continue
                raw = nums[0]
                int_part = re.split(r"[.,]", raw)[0]
                try:
                    return int(int_part)
                except ValueError:
                    continue
            break
    return None

def _strip_accents(s: str) -> str:
    if s is None:
        return ""
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")

def extract_descrierea_marfurilor(text: str) -> str:
    """
    Extract the 'Descrierea mărfurilor [18 05]' cell from the text.
    Removes a leading article number and trims any trailing neighbour header data.
    """
    if not text:
        return ""
    clean = _strip_accents(text)
    orig_lines = text.splitlines()
    clean_lines = clean.splitlines()
    hdr = re.compile(r"Descrierea\s+marfurilor\s*\[\s*18\s*0?5\s*\]", re.I)
    # Headers that indicate we've reached the next section
    stopper = re.compile(
        r"^(?:Expeditor|Destinatar|Tipul\s+si\s+nr\.|Tipul\s+și\s+nr\.|Cod\s+CUS|Cod\s+ONU|Cod\s+de\s+nomenclatura"
        r"|Unitati\s+suplim\.|Tara\s+exportatoare|Tara\s+de\s+destinatie|Masa\s+bruta|Masa\s+neta|Reg\.)",
        re.I,
    )
    for i, line in enumerate(clean_lines):
        if hdr.search(line):
            j = i + 1
            # skip blank lines
            while j < len(clean_lines) and not clean_lines[j].strip():
                j += 1
            collected: List[str] = []
            while j < len(clean_lines):
                if stopper.search(clean_lines[j]):
                    break
                if not clean_lines[j].strip():
                    if collected:
                        break
                    j += 1
                    continue
                collected.append(orig_lines[j])
                j += 1
            if not collected:
                return ""
            cleaned_lines: List[str] = []
            for raw in collected:
                # remove leading integer token (article number)
                m = re.match(r"\s*(\d+)\s+(.*)", raw)
                candidate = m.group(2) if m else raw
                # strip trailing neighbour header pieces
                candidate = re.sub(r"\s*(Tipul\s+și\s+nr\.|Tipul\s+si\s+nr\.).*", "", candidate, flags=re.I)
                candidate = re.sub(r"\s*Cod\s+CUS.*", "", candidate, flags=re.I)
                candidate = re.sub(r"\s*Cod\s+ONU.*", "", candidate, flags=re.I)
                candidate = re.sub(r"\s*\[\s*18\s*0?6\s*\].*", "", candidate)
                candidate = candidate.strip()
                if candidate:
                    cleaned_lines.append(candidate)
            return " ".join(cleaned_lines).strip()
    return ""

def extract_fields(text: str) -> dict:
    """Parse the extracted text and return a dictionary of fields."""
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    data: dict = {}
    # date [15 09]
    for l in lines:
        if "[15 09]" in l:
            m = re.search(r"(\d{2}-\d{2})-\d{4}", l)
            if m:
                data["dataDeclaratie"] = m.group(1)
            break
    mrn = find_mrn(lines)
    if mrn:
        data["nrMrn"] = mrn
    ident = extract_identificare(text)
    if ident:
        data["identificare"] = ident
    # exporter name
    for i, l in enumerate(lines):
        if "Exportator [13 01]" in l:
            if i + 1 < len(lines):
                data["numeExportator"] = lines[i + 1].strip()
            break
    buc = extract_buc(lines)
    if buc is not None:
        data["buc"] = buc
    greutate = extract_greutate(lines)
    if greutate is not None:
        data["greutate"] = greutate
    desc = extract_descrierea_marfurilor(text)
    if desc:
        data["descriereaMarfurilor"] = desc
    return data

def main(folder_path: str) -> None:
    results = []
    for pdf_path in Path(folder_path).glob("*.pdf"):
        try:
            text = pdf_to_text(str(pdf_path))
            rec = extract_fields(text)
            rec["file"] = pdf_path.name
        except Exception as e:
            rec = {"file": pdf_path.name, "error": str(e)}
        results.append(rec)
    print(json.dumps(results, ensure_ascii=False))

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: extract.py <folder>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
