"""Resume os relatórios JUnit das jornadas no navegador em markdown."""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main(directory: str) -> None:
    reports = sorted(Path(directory).glob("TEST-*BrowserIT.xml"))
    print("## Jornadas no navegador\n")
    if not reports:
        print("Nenhum relatório encontrado; a suíte não chegou a executar.")
        return

    rows = []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
        for case in suite.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                result = "falhou"
            elif case.find("skipped") is not None:
                result = "ignorado"
            else:
                result = "passou"
            name = case.get("name", "").replace("|", "/")
            rows.append(f"| {name} | {result} | {float(case.get('time', '0')):.1f}s |")

    print(
        f"{totals['tests']} cenários, {totals['failures']} falhas, "
        f"{totals['errors']} erros, {totals['skipped']} ignorados.\n"
    )
    print("| Cenário | Resultado | Tempo |")
    print("| --- | --- | --- |")
    print("\n".join(rows))
    print("\nCapturas e relatórios ficam no artefato `testes-navegador` deste job.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "target/surefire-reports")
