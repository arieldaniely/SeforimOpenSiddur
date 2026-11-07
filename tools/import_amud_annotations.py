"""Export only unambiguous Amud conditions for whole paragraphs in a Zayit database."""

import argparse
from collections import Counter, defaultdict
import hashlib
import html
import json
from pathlib import Path
import re
import sqlite3


BOOKS = {
    "ashkenaz": ("ASHKENAZ", "סידור אשכנז"),
    "sefard": ("SEFARD", "סידור ספרד"),
    "edot_hamizrach": ("EDOT_HAMIZRACH", "סידור נוסח עדות המזרח"),
}

FLAGS = {
    "aseretYemeiTeshuva": "aseret_yemei_teshuva",
    "chanukah": "chanuka",
    "cholHamoed": "chol_hamoed",
    "diaspora": "!israel",
    "halfHallel": "chatzi_hallel",
    "il": "israel",
    "mashivHaruach": "mashiv_haruach",
    "minyan": "minyan",
    "moridHatal": "morid_hatal",
    "omer": "omer",
    "purim": "purim",
    "roshChodesh": "rosh_chodesh",
    "shabbat": "sat",
    "talUmatar": "tal_umatar",
    "torahReading": "torah_reading",
    "wholeHallel": "hallel_shalem",
}


def fingerprint(text):
    letters = re.sub(r"[^א-ת]", "", html.unescape(re.sub(r"<[^>]+>", "", text)))
    if len(letters) < 24:
        return None
    return hashlib.sha256(letters.encode("utf-8")).hexdigest()


def condition(expression):
    """Preserve OR clauses and AND terms. Reject other syntax and unknown facts."""
    alternatives = []
    for clause in expression.split("||"):
        terms = []
        for term in clause.split("&&"):
            term = term.strip()
            if term == "true":
                continue
            flag = FLAGS.get(term.lstrip("!"))
            if flag is None:
                return None
            terms.append(("!" + flag if not flag.startswith("!") else flag[1:]) if term.startswith("!") else flag)
        if not terms:
            return ""
        alternatives.append("&".join(sorted(set(terms))))
    return "|".join(sorted(set(alternatives)))


def with_minyan(expression):
    if expression is None:
        return None
    return "|".join("&".join(sorted(set(filter(None, clause.split("&"))) | {"minyan"}))
                    for clause in expression.split("|"))


def annotations(directory):
    rules = defaultdict(set)
    for path in sorted(directory.glob("*.json")):
        for leaf in json.loads(path.read_text(encoding="utf-8")).get("leaves", ()):
            leaf_condition = leaf.get("when", "true")
            for segment in leaf.get("he", {}).get("segs", ()):
                parts = segment.get("parts", (segment,))
                key = fingerprint("".join(part["text"] for part in parts))
                if key is None:
                    continue
                for part in parts:
                    part_condition = part.get("when", "true")
                    # An OR inherited at one level would need parentheses to combine it with the other.
                    if leaf_condition == "true":
                        rule = condition(part_condition)
                    elif part_condition == "true":
                        rule = condition(leaf_condition)
                    elif "||" not in leaf_condition and "||" not in part_condition:
                        rule = condition(leaf_condition + " && " + part_condition)
                    else:
                        rule = None
                    rules[key].add(with_minyan(rule) if part.get("minyan") else rule)
    return rules


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("amud", type=Path, help="Path to the Amud source checkout")
    parser.add_argument("database", type=Path, help="Path to seforim.db")
    args = parser.parse_args()
    output = Path(__file__).resolve().parents[1] / "src/jvmMain/resources/open-siddur/amud-annotations.tsv"
    rows = []
    with sqlite3.connect(args.database.resolve().as_uri() + "?mode=ro&immutable=1", uri=True) as database:
        for folder, (nusach, title) in BOOKS.items():
            corpus = database.execute(
                "SELECT l.content FROM line l JOIN book b ON b.id = l.bookId WHERE b.title = ?", (title,),
            )
            occurrences = Counter(fingerprint(text) for (text,) in corpus)
            for key, choices in sorted(annotations(args.amud / "corpus" / "siddur" / folder).items()):
                if occurrences[key] == 1 and len(choices) == 1:
                    rule = next(iter(choices))
                    if rule:
                        rows.append(f"{nusach}\t{key}\t{rule}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(rows)} unambiguous whole-paragraph annotations")


if __name__ == "__main__":
    main()
