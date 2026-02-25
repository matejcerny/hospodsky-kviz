# Hospodský kvíz – Automatická kontrola týmů

## Spuštění skriptu
Skript se spouští z terminálu a vyžaduje zadání PINu kvízu jako parametru. Pokud jsi soubor uložil například pod názvem kontrola_tymu.py, spuštění vypadá takto:

Základní formát:

```shell
./kontrola_tymu 123456
```

(Skript po spuštění stáhne týmy z webové rezervace, porovná je se systémem a případně dohledá chybějící týmy v globální databázi Hospodského kvízu).
