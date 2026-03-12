//> using scala "3.8.2"
//> using platform "native"
//> using dep "com.softwaremill.sttp.client4::core::4.0.19"
//> using dep "io.circe::circe-core::0.14.15"
//> using dep "io.circe::circe-parser::0.14.15"

import sttp.client4.*
import sttp.client4.curl.CurlBackend
import io.circe.parser.*
import io.circe.Json
import scala.io.StdIn

// Pomocné čisté funkce na nejvyšší úrovni souboru
def stripTags(html: String): String = html.replaceAll("(?is)<[^>]+>", "").trim

def extractTableRows(html: String): List[String] =
  "(?is)<tr[^>]*>(.*?)</tr>".r.findAllMatchIn(html).map(_.group(1)).toList

def extractCols(row: String): List[String] =
  "(?is)<t[dh][^>]*>(.*?)</t[dh]>".r.findAllMatchIn(row).map(m => stripTags(m.group(1))).toList

@main def kontrolaTymu(): Unit =
  println("Hospodský kvíz")
  print("Zadej PIN pro aktuální kvíz a stiskni Enter: ")
  scala.Console.flush()
  
  val inputPin = StdIn.readLine()
  if inputPin == null || inputPin.trim.isEmpty then
    println("Chyba: Nebyl zadán žádný PIN.")
    sys.exit(1)
    
  val pin = inputPin.trim

  val backend = CurlBackend()
  val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

  // --- ČÁST A: Získání týmů z webu ---
  println("Stahuji seznam týmů z webu (rezervace)...")
  val urlWeb = uri"https://www.hospodskykviz.cz/hospody/u-posty-vinohrady-st/rezervace"
  val responseWeb = basicRequest.header("User-Agent", userAgent).get(urlWeb).send(backend)

  // Deklarativní zpracování HTML bez závorek
  val upcomingTeams: List[String] = responseWeb.body.toOption.toList.flatMap: html =>
    val tables = "(?is)<table[^>]*>(.*?)</table>".r.findAllMatchIn(html).map(_.group(1)).toList
    
    tables.find(t => extractTableRows(t).headOption.exists(_.contains("Název týmu")))
      .toList
      .flatMap(extractTableRows)
      .drop(1)
      .map(extractCols)
      .filter(_.length >= 2)
      .map(_.head)
      .filter(name => name != "Toto místo je zatím volné." && name.nonEmpty)

  if upcomingTeams.isEmpty then
    println("Chyba: Nepodařilo se stáhnout žádné týmy z webu nebo je stránka prázdná.")
    sys.exit(1)

  // --- ČÁST B: Získání týmů z API ---
  println(s"Stahuji seznam týmů z API pro PIN: $pin (simuluji mobilní Chrome)...")
  val urlApi = uri"https://www.hospodskykviz.cz/api/loadquiz"
  val responseApi = basicRequest
    .header("User-Agent", userAgent)
    .header("Origin", "https://hodnoceni.hospodskykviz.cz")
    .header("Accept", "application/json, text/javascript, */*; q=0.01")
    .body(Map("pin" -> pin))
    .post(urlApi)
    .send(backend)

  // Funkcionální zpracování chyb přes for-comprehension bez složených závorek
  val registeredTeamsEither: Either[String, List[String]] =
    for
      body <- responseApi.body
      json <- parse(body).left.map(_ => "Odpověď serveru není platný JSON.")
      teamsJson <- json.hcursor.downField("teams").as[List[Json]].left.map(_ => "Chybí pole 'teams'.")
    yield teamsJson.flatMap(_.hcursor.downField("name").as[String].toOption).map(_.trim)

  val registeredTeams = registeredTeamsEither match
    case Right(teams) => teams
    case Left(errorMsg) =>
      println(s"Chyba při komunikaci s API: $errorMsg")
      sys.exit(1)
      List.empty

  // --- ČÁST C: Porovnání obou seznamů ---
  println("\n--- VÝSLEDEK KONTROLY ---")
  val registeredTeamsLower = registeredTeams.map(_.toLowerCase)
  val missingTeams = upcomingTeams.filterNot(t => registeredTeamsLower.contains(t.toLowerCase))

  if missingTeams.isEmpty then
    println("✅ Vše v pořádku! Všechny týmy z webové rezervace jsou zadané v hodnotícím systému.")
  else
    println(s"Nalezeno ${missingTeams.length} chybějících týmů. Ověřuji v globální databázi...\n")

    // --- ČÁST D: Ověření chybějících týmů v DB ---
    missingTeams.foreach: team =>
      val searchUrl = uri"https://www.hospodskykviz.cz/tymy/seznam?search=$team"
      val resSearch = basicRequest.header("User-Agent", userAgent).get(searchUrl).send(backend)

      val dbMatch: Option[(String, String)] = resSearch.body.toOption.flatMap: htmlSearch =>
        val searchTables = "(?is)<table[^>]*>(.*?)</table>".r.findAllMatchIn(htmlSearch).map(_.group(1)).toList
        
        searchTables.find(t => extractTableRows(t).headOption.exists(_.contains("Název domovské hospody")))
          .map(t => extractTableRows(t).drop(1))
          .filter(_.nonEmpty)
          .flatMap: dataRows =>
            val parsedRows = dataRows.map(extractCols).filter(_.length >= 2)
            parsedRows.find(_.head.toLowerCase == team.toLowerCase)
              .orElse(parsedRows.headOption)
      .map(cols => (cols(0), cols(1)))

      dbMatch match
        case Some((dbTeamName, homePub)) =>
          println(s" ⚠️  Tým '$team' nalezen. Domovská hospoda: $homePub (v DB jako '$dbTeamName')")
        case None =>
          println(s" ❌ Tým '$team' NENÍ v databázi. Tým se musí založit!")

  println("\nHotovo.")
  println("Stiskni Enter pro ukončení...")
  StdIn.readLine()