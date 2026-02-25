import sys
import requests
from bs4 import BeautifulSoup
import urllib.parse

# 1. Kontrola a načtení PINu z parametrů při spuštění
if len(sys.argv) < 2:
    print("Chyba: Nebyl zadán PIN.")
    sys.exit(1)

pin = sys.argv[1]

# Společné hlavičky pro web
headers_web = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

# --- ČÁST A: Získání týmů z webu (upcoming_teams) ---
print("Stahuji seznam týmů z webu (rezervace)...")
url_web = "https://www.hospodskykviz.cz/hospody/u-posty-vinohrady-st/rezervace"

upcoming_teams = []
response_web = requests.get(url_web, headers=headers_web)

if response_web.status_code == 200:
    soup = BeautifulSoup(response_web.text, 'html.parser')
    tables = soup.find_all('table')
    
    for table in tables:
        first_row = table.find('tr')
        if first_row and 'Název týmu' in first_row.text:
            rows = table.find_all('tr')[1:]
            for row in rows:
                cols = row.find_all(['td', 'th'])
                if len(cols) >= 2:
                    team_name = cols[0].text.strip()
                    if team_name != "Toto místo je zatím volné.":
                        upcoming_teams.append(team_name)
            break
else:
    print(f"Chyba při stahování webu. Kód: {response_web.status_code}")
    sys.exit(1)


# --- ČÁST B: Získání týmů z API pomocí PINu (registered_teams) ---
print(f"Stahuji seznam týmů z API pro PIN: {pin} (simuluji mobilní Chrome)...")
url_api = "https://www.hospodskykviz.cz/api/loadquiz"

headers_api = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Origin": "https://hodnoceni.hospodskykviz.cz",
    "Accept": "application/json, text/javascript, */*; q=0.01"
}
payload = {
    "pin": pin
}

registered_teams = []
response_api = requests.post(url_api, headers=headers_api, data=payload)

if response_api.status_code == 200:
    try:
        data = response_api.json()
        teams_data = data.get("teams", [])
        
        for team in teams_data:
            name = team.get("name")
            if name:
                registered_teams.append(name.strip())
    except ValueError:
        print("Chyba: Odpověď serveru není platný JSON.")
        sys.exit(1)
else:
    print(f"Chyba při komunikaci s API. Kód: {response_api.status_code}")
    sys.exit(1)


# --- ČÁST C: Porovnání obou seznamů ---
print("\n--- VÝSLEDEK KONTROLY ---")

missing_teams = []
registered_teams_lower = [t.lower() for t in registered_teams]

for team in upcoming_teams:
    if team.lower() not in registered_teams_lower:
        missing_teams.append(team)

if not missing_teams:
    print("✅ Vše v pořádku! Všechny týmy z webové rezervace jsou zadané v hodnotícím systému.")
else:
    print(f"Nalezeno {len(missing_teams)} chybějících týmů. Ověřuji v globální databázi...\n")
    
    # --- ČÁST D: Ověření chybějících týmů v databázi Hospodského kvízu ---
    for team in missing_teams:
        # Převedeme název týmu do formátu bezpečného pro URL (např. mezery na %20)
        search_url = f"https://www.hospodskykviz.cz/tymy/seznam?search={urllib.parse.quote(team)}"
        res_search = requests.get(search_url, headers=headers_web)
        
        found_in_db = False
        
        if res_search.status_code == 200:
            soup_search = BeautifulSoup(res_search.text, 'html.parser')
            search_tables = soup_search.find_all('table')
            
            for table in search_tables:
                first_row = table.find('tr')
                # Zkontrolujeme, zda jde o správnou tabulku s výsledky
                if first_row and 'Název domovské hospody' in first_row.text:
                    rows = table.find_all('tr')[1:] # Přeskočíme hlavičku
                    
                    if rows:
                        best_match_cols = None
                        
                        # 1. Nejprve zkusíme najít přesnou shodu jména (bez ohledu na velikost písmen)
                        for row in rows:
                            cols = row.find_all('td')
                            if len(cols) >= 2:
                                db_team_name = cols[0].text.strip()
                                if team.lower() == db_team_name.lower():
                                    best_match_cols = cols
                                    break
                        
                        # 2. Pokud se nenašla přesná shoda, vezmeme prostě první výsledek vyhledávání
                        if not best_match_cols:
                            cols = rows[0].find_all('td')
                            if len(cols) >= 2:
                                best_match_cols = cols
                        
                        # Pokud máme data, vypíšeme je
                        if best_match_cols:
                            db_team_name = best_match_cols[0].text.strip()
                            home_pub = best_match_cols[1].text.strip()
                            print(f" ⚠️  Tým '{team}' nalezen. Domovská hospoda: {home_pub} (v DB jako '{db_team_name}')")
                            found_in_db = True
                            break
        
        if not found_in_db:
            print(f" ❌ Tým '{team}' NENÍ v databázi. Tým se musí založit!")

print("\nHotovo.")