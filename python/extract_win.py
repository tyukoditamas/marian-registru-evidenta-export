#!/usr/bin/env python3
import os, sys, json, re, shutil, subprocess, unicodedata
from pathlib import Path

MRN_RE = re.compile(r"\b(25RO[A-Z0-9]{6,})\b", re.I)

def pdf_to_text(path: str) -> str:
    # Prefer bundled binary from the Java app
    bin_path = os.environ.get("PDFTOTEXT_BIN")
    if bin_path and os.path.exists(bin_path):
        exe = bin_path
    else:
        exe = shutil.which("pdftotext")
        if not exe:
            raise RuntimeError("Missing dependency: 'pdftotext' (Poppler/Xpdf)")
    # Force UTF-8 + Unix EOL for stable parsing
    cmd = [exe, "-enc", "UTF-8", "-eol", "unix", "-layout", path, "-"]
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=False)
    if r.returncode != 0:
        raise RuntimeError((r.stderr.decode("utf-8", "replace") or f"pdftotext exit {r.returncode}").strip())
    try:
        return r.stdout.decode("utf-8")
    except UnicodeDecodeError:
        return r.stdout.decode("utf-8", "replace")

def _strip_accents(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")

def extract_identificare(full_text: str):
    hdr = list(re.finditer(r"Documentul de transport\s*\[\s*12\s*05\s*\]", full_text, flags=re.I))
    if not hdr: return None
    ends = [m.start() for m in re.finditer(r"Documentul precedent\s*\[\s*12\s*01\s*\]", full_text, flags=re.I)]
    for m in hdr:
        end_candidates = [e for e in ends if e > m.end()]
        end_pos = min(end_candidates) if end_candidates else len(full_text)
        seg = full_text[m.end():end_pos]
        if re.search(r"\bN\s*740\b|\bN\s*741\b", seg, flags=re.I): return "AWB"
        if re.search(r"\bN\s*730\b", seg, flags=re.I): return "CMR"
    return None

def extract_buc(lines):
    for i, l in enumerate(lines):
        if re.search(r"\[\s*18\s*06\s*\]", l):
            for j in range(i + 1, min(i + 6, len(lines))):
                s = re.sub(r"\s+", " ", lines[j].strip())
                if not s: continue
                m = re.search(r"/\s*(?:PC|PX)\s*/\s*(\d+)\s*/", s, flags=re.I)
                if m: return int(m.group(1))
                parts = [p.strip() for p in s.split("/") if p.strip()]
                if len(parts) >= 3 and re.fullmatch(r"\d+", parts[-2]): return int(parts[-2])
            break
    return None

def find_mrn(lines):
    for i, line in enumerate(lines):
        if re.search(r"\bMRN\b", line, re.I):
            window = lines[max(0,i-3):min(len(lines), i+8)]
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

def extract_greutate(lines):
    hdr = re.compile(r"Masa\s+brut[ăa]\s*\[\s*18\s*04\s*\]", re.I)
    num = re.compile(r"(?<![A-Za-z0-9])(\d{2,}(?:[.,]\d{3})*|\d{2,}(?:[.,]\d+)?)(?![A-Za-z0-9])")
    for i, l in enumerate(lines):
        if hdr.search(l):
            for j in range(i+1, min(i+4, len(lines))):
                cand = re.sub(r"\s+", " ", lines[j].strip())
                if not cand or "[" in cand: continue
                nums = [m.group(1) for m in num.finditer(cand)]
                if not nums: continue
                raw = nums[0]
                return int(re.split(r"[.,]", raw)[0])
            break
    return None

def extract_descrierea_marfurilor(text: str) -> str:
    if not text: return ""
    clean = _strip_accents(text)
    orig_lines = text.splitlines()
    clean_lines = clean.splitlines()
    hdr = re.compile(r"Descrierea\s+marfurilor\s*\[\s*18\s*0?5\s*\]", re.I)
    stopper = re.compile(
        r"^(?:Expeditor|Destinatar|Tipul\s+si\s+nr\.|Tipul\s+și\s+nr\.|Cod\s+CUS|Cod\s+ONU|Masa\s+bruta|Masa\s+neta|Tara\s+de\s+destinatie|Tara\s+exportatoare|Reg\.)\b",
        re.I
    )
    for i, line in enumerate(clean_lines):
        if hdr.search(line):
            j = i+1
            while j < len(clean_lines) and not clean_lines[j].strip(): j += 1
            collected = []
            while j < len(clean_lines):
                if stopper.search(clean_lines[j]): break
                if not clean_lines[j].strip() and collected: break
                collected.append(orig_lines[j])
                j += 1
            if not collected: return ""
            out = []
            for raw in collected:
                m = re.match(r"\s*(\d+)\s+(.*)", raw)  # strip leading article number
                s = m.group(2) if m else raw
                s = re.sub(r"\s*(Tipul\s+și\s+nr\.|Tipul\s+si\s+nr\.).*", "", s, flags=re.I)
                s = re.sub(r"\s*Cod\s+CUS.*", "", s, flags=re.I)
                s = re.sub(r"\s*Cod\s+ONU.*", "", s, flags=re.I)
                s = re.sub(r"\s*\[\s*18\s*0?6\s*\].*", "", s)
                s = s.strip()
                if s: out.append(s)
            return " ".join(out).strip()
    return ""

def extract_fields(text: str) -> dict:
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    data = {}
    for l in lines:
        if "[15 09]" in l:
            m = re.search(r"(\d{2}-\d{2})-\d{4}", l)
            if m: data["dataDeclaratie"] = m.group(1); break
    mrn = find_mrn(lines)
    if mrn: data["nrMrn"] = mrn
    ident = extract_identificare(text)
    if ident: data["identificare"] = ident
    for i, l in enumerate(lines):
        if "Exportator [13 01]" in l and i+1 < len(lines):
            data["numeExportator"] = lines[i+1].strip(); break
    buc = extract_buc(lines)
    if buc is not None: data["buc"] = buc
    gre = extract_greutate(lines)
    if gre is not None: data["greutate"] = gre
    desc = extract_descrierea_marfurilor(text)
    if desc: data["descriereaMarfurilor"] = desc
    return data

def main(folder: str):
    results = []
    for pdf in Path(folder).glob("*.pdf"):
        try:
            text = pdf_to_text(str(pdf))
            rec = extract_fields(text)
            rec["file"] = pdf.name
        except Exception as e:
            rec = {"file": pdf.name, "error": str(e)}
        results.append(rec)
    print(json.dumps(results, ensure_ascii=False))

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: extract_win.py <folder>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
