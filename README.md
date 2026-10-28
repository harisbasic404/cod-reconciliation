# COD Reconciliation System

Web aplikacija za usklađivanje pouzećem naplaćenih pošiljki sa izvještajima kurirske službe. Aplikacija vodi narudžbe, uvozi XLSX izvještaj, upoređuje očekivani i naplaćeni iznos, čuva svaku obradu kao historijski batch i omogućava izvoz rezultata.

**COD (Cash on Delivery)** znači da kupac plaća robu prilikom dostave. Kurir potom dostavlja izvještaj o naplaćenim iznosima. Reconciliacija povezuje red izvještaja s narudžbom preko broja za praćenje, poredi iznose i bilježi rezultat: podudaranje, razliku, nepoznatu narudžbu ili grešku ulaznog reda.

## Tehnologije

| Tehnologija | Potvrđena upotreba |
| --- | --- |
| Java | 21 (`pom.xml`); projekat je testiran i s JDK 27 |
| Spring Boot | 3.5.6 |
| Maven Wrapper | Maven 3.10.0 (`mvnw.cmd`) |
| Spring Web, Validation i Security | REST API, validacija, HTTP Basic i CSRF |
| Spring Data JPA / Hibernate | perzistencija entiteta u MySQL-u |
| MySQL Connector/J | produkcijska i lokalna konekcija |
| Flyway | verzionirane migracije V1–V3 |
| Apache POI | `poi-ooxml` 5.4.1, rad s XLSX fajlovima |
| OpenPDF | 2.0.3, PDF izvodi i informativna faktura |
| HTML, CSS i JavaScript | statični frontend bez Node build procesa |
| JUnit Jupiter, Spring Boot Test, Spring Security Test i H2 | automatizovani testovi u izolovanoj memorijskoj bazi |

## Struktura projekta

```text
src/main/java/com/example/codreconciliation/
  config/          sigurnosna i DB provjera, početni demonstracijski podaci
  controller/      REST endpointi za narudžbe, reconciliaciju, dashboard, CSRF i health
  dto/             ulazni i izlazni modeli API-ja
  entity/          Order, ReconciliationBatch i ReconciliationItem
  enums/           statusi narudžbe, ishodi i filter historije
  exception/       poslovne greške i centralno mapiranje HTTP odgovora
  repository/      Spring Data JPA upiti
  service/         XLSX parser, reconciliacija, uvoz narudžbi i PDF izvoz
src/main/resources/
  application.properties       produkcijska/lokalna konfiguracija kroz varijable okruženja
  db/migration/                Flyway migracije V1, V2 i V3
  static/                      HTML, CSS i JavaScript frontend
src/test/                      H2 testni profil i integracijski/jedinični testovi
run-local.ps1                  provjereno lokalno pokretanje s validacijom konfiguracije
generate-bcrypt.ps1            jednokratno, lokalno generisanje BCrypt hasha
.env.example                   primjer naziva lokalnih varijabli bez stvarnih tajni
```

## Preduslovi na Windowsu

Potrebni su JDK 21 ili noviji i MySQL 8 ili noviji. Maven nije nužan jer projekat uključuje Maven Wrapper. PowerShell 5.1 ili noviji je potreban za pomoćne skripte.

```powershell
java -version
mysql --version
.\mvnw.cmd -version
```

Ako koristite zasebno instaliran Maven, možete provjeriti i `mvn -version`, ali komande u nastavku koriste Wrapper radi ponovljivosti.

## MySQL baza

Prijavite se na MySQL administrativnim nalogom i kreirajte bazu te namjenskog korisnika. Zamijenite tekst u uglastim zagradama vlastitom lokalnom lozinkom; ne unosite je u repozitorij.

```sql
CREATE DATABASE cod_reconciliation CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'cod_app'@'localhost' IDENTIFIED BY '[lokalna-lozinka]';
GRANT ALL PRIVILEGES ON cod_reconciliation.* TO 'cod_app'@'localhost';
FLUSH PRIVILEGES;
```

Za lokalni razvoj su ove privilegije dovoljne zato što Flyway kreira i mijenja samo objekte u bazi `cod_reconciliation`. Ne koristite `root` kao aplikacijski nalog. Konekciju provjerite bez prikaza lozinke:

```powershell
mysql -u cod_app -p -h localhost cod_reconciliation -e "SELECT 1;"
```

Pri startu Flyway izvršava još neprimijenjene verzionirane migracije iz `src/main/resources/db/migration`, zatim Hibernate s `ddl-auto=validate` provjerava mapiranje. Migracije se ne resetuju niti brišu postojeće poslovne podatke. Ako je baza potpuno prazna, `InitialOrderData` doda pet demonstracijskih narudžbi; na nepraznoj bazi ne dodaje ništa.

## Varijable okruženja

| Varijabla | Obavezna | Značenje i podrazumijevana vrijednost |
| --- | --- | --- |
| `DB_URL` | da | MySQL JDBC URL; nema podrazumijevanu vrijednost |
| `DB_USERNAME` | da | korisnik baze; nema podrazumijevanu vrijednost |
| `DB_PASSWORD` | da | lozinka baze; nema podrazumijevanu vrijednost |
| `ADMIN_USERNAME` | da | ime jedinog administratorskog korisnika |
| `ADMIN_PASSWORD_HASH` | da | isključivo `{bcrypt}` BCrypt hash administratorske lozinke |
| `APP_ENV` | ne za ručni Maven start, da za `run-local.ps1` | `application.properties` podrazumijeva `production`; skripta postavlja `local` ako nije zadan |
| `HSTS_ENABLED` | ne | `false`; postavite `true` samo iza pravilno konfigurisanog HTTPS-a |
| `SERVER_PORT` | ne | `8080` |

Lokalni JDBC URL mora eksplicitno koristiti TLS, na primjer:

```text
jdbc:mysql://localhost:3306/cod_reconciliation?sslMode=REQUIRED&serverTimezone=UTC
```

`run-local.ps1` odbija URL bez `sslMode=REQUIRED` ili `sslMode=VERIFY_IDENTITY`, kao i URL koji isključuje TLS. `REQUIRED` šifrira vezu, ali sam ne potvrđuje identitet MySQL servera. Za produkciju postavite `APP_ENV=production`, koristite `sslMode=VERIFY_IDENTITY` i konfiguraciju CA/trust store-a koju daje provajder baze.

Možete lokalno napraviti `.env` prema `.env.example`; on je ignorisan Gitom i Spring ga učitava kao opcionalni properties fajl. `run-local.ps1` ipak čita varijable trenutnog PowerShell procesa, zato ih prije poziva skripte postavite u istom prozoru.

### Sigurno generisanje administratorskog hasha

Skripta `generate-bcrypt.ps1` koristi postojeću Spring Security zavisnost, traži lozinku kroz interaktivni konzolni unos i privremene Java fajlove pravi izvan projekta. Pokrenite je samo u interaktivnom PowerShell prozoru:

```powershell
.\generate-bcrypt.ps1
```

Skripta ispiše jedan `{bcrypt}...` rezultat. Postavite ga samo u trenutni proces; ne stavljajte ni lozinku ni hash u README, `.env.example` ili Git historiju.

```powershell
$env:ADMIN_PASSWORD_HASH = "{bcrypt}<lokalno-generisani-hash>"
```

## Pokretanje aplikacije

U novom PowerShell prozoru postavite sve obavezne varijable. Primjer koristi zamjenske vrijednosti, ne stvarne pristupne podatke.

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/cod_reconciliation?sslMode=REQUIRED&serverTimezone=UTC"
$env:DB_USERNAME = "cod_app"
$env:DB_PASSWORD = "<lokalna-lozinka>"
$env:ADMIN_USERNAME = "cod-admin"
$env:ADMIN_PASSWORD_HASH = "{bcrypt}<lokalno-generisani-hash>"
$env:APP_ENV = "local"
.\run-local.ps1
```

Skripta provjerava prisustvo konfiguracije, validira format BCrypt hasha i TLS postavke prije poziva `mvnw.cmd spring-boot:run`. Ako varijable već nisu postavljene, za DB URL, MySQL korisnika, MySQL lozinku i administratorsko ime će tražiti unos; hash se mora postaviti unaprijed.

Za ručno pokretanje iz istog, već konfigurisanog prozora:

```powershell
.\mvnw.cmd spring-boot:run
```

Zaustavite aplikaciju s `Ctrl+C`. Za ponovno pokretanje prekinite proces i ponovo izvršite jednu od prethodnih komandi. `SERVER_PORT` možete postaviti prije starta ako je port 8080 zauzet.

## Pristup i prijava

Frontend je dostupan na `http://localhost:8080/`, a health provjera na `GET http://localhost:8080/api/health`. Samo health endpoint i statični fajlovi su javni. Sve ostale `/api/**` rute traže HTTP Basic prijavu korisnikom iz `ADMIN_USERNAME` i njegovom stvarnom lozinkom, ne hashom.

Frontend prikazuje prijavni obrazac, dohvaća CSRF token s `GET /api/csrf` nakon prijave i šalje ga za POST i PUT zahtjeve. Basic akreditiv i CSRF token čuvaju se samo u memoriji stranice; odjava ih briše. API klijent izvan frontenda mora poslati Basic autorizaciju i CSRF cookie/zaglavlje za izmjene stanja.

Brza provjera nakon uspješnog starta ne mijenja podatke:

```powershell
Invoke-RestMethod http://localhost:8080/api/health
```

Očekivani odgovor je JSON sa statusom `UP`.

## Funkcionalnosti

- Dashboard prikazuje broj narudžbi po statusu i zbirne historijske rezultate batch-eva.
- Pregled narudžbi podržava pretragu po tracking broju ili kupcu, filtriranje statusa, kreiranje, izmjenu i detalj sa historijom reconciliacije.
- Uvoz narudžbi je dvofazni: XLSX se prvo validira u kratkotrajnom preview-u, a potvrda ponovo provjerava konflikte u transakciji.
- Upload kurirskog XLSX izvještaja pravi batch i trajno čuva rezultat svakog upotrebljivog reda.
- Historija podržava pretragu, datumski raspon i filtre ishoda, te XLSX/PDF izvoz batch-a. Dostupan je i informativni PDF narudžbe.

## Format kurirskog Excel izvještaja

Prihvaćen je samo `.xlsx` fajl do 10 MB. Čita se prvi radni list. Neobavezne, nepovezane kolone se ignorišu, a tri obavezne kolone mogu biti bilo kojim redoslijedom.

| Podatak | Prihvaćena zaglavlja | Pravila |
| --- | --- | --- |
| Tracking broj | `Tracking Number`, `Tracking No`, `Tracking ID`, `Broj pošiljke`, `Broj za praćenje` | najviše 64 znaka; slova, cifre i crtice |
| Naplaćeni iznos | `Collected Amount`, `COD Amount`, `Collected COD`, `Iznos otkupnine`, `Naplaćeni iznos` | nenegativan `BigDecimal`, najviše dvije decimale |
| Datum dostave | `Delivery Date`, `Delivered Date`, `Date Delivered`, `Datum isporuke`, `Datum dostave` | Excel datum ili `yyyy-MM-dd`, `dd.MM.yyyy`, `dd/MM/yyyy`, `MM/dd/yyyy` |

Zaglavlja se porede neovisno o velikim/malim slovima, dodatnim razmacima i dijakriticima. Prazni redovi se preskaču. Nepotpuni ili neispravni redovi ostaju u batch-u kao `ERROR`; duplirani tracking broj unutar istog izvještaja takođe ostaje `ERROR`, a samo prva pojava se obrađuje. Nepoznat tracking broj daje `NOT_FOUND` i ne kreira novu narudžbu. Kod postojećeg naloga poređenje iznosa radi `BigDecimal.compareTo`: jednak iznos daje `RECONCILED`, a različit `AMOUNT_MISMATCH`. Identičan sadržaj istog izvještaja ne može biti ponovo obrađen, jer se čuva SHA-256 hash sadržaja.

Za uvoz narudžbi format je zaseban: `Tracking Number`, `Customer Name` i `Expected Amount`; podržani su i stvarni aliasi koje prihvata servis. Status iz ulazne tabele se ne uvozi — nove narudžbe uvijek počinju kao `PENDING`.

## REST API

Svaka navedena ruta osim `/api/health` zahtijeva administratora. Uspješni odgovori koriste DTO modele, a greške imaju polja `timestamp`, `status`, `message` i `path`.

| Metoda i putanja | Namjena, ulaz i uspješan odgovor | Važni statusi |
| --- | --- | --- |
| `GET /api/health` | Javna provjera rada; vraća status. | `200` |
| `GET /api/csrf` | Daje CSRF token i postavlja CSRF cookie za prijavljenog administratora. | `200`, `401` |
| `GET /api/dashboard/stats` | Zbirna statistika dashboarda. | `200`, `401` |
| `GET /api/orders` | Lista narudžbi; opcionalno `search` i `status` (`PENDING`, `RECONCILED`, `AMOUNT_MISMATCH`). | `200`, `400`, `401` |
| `GET /api/orders/{id}` | Jedna narudžba i njena historija. | `200`, `401`, `404` |
| `POST /api/orders` | JSON: `trackingNumber`, `customerName`, `expectedAmount`; kreira `PENDING` narudžbu. | `201`, `400`, `401`, `403`, `409` |
| `PUT /api/orders/{id}` | Isti JSON kao kreiranje; mijenja osnovne podatke bez brisanja historije. | `200`, `400`, `401`, `403`, `404`, `409` |
| `POST /api/orders/import/preview` | Multipart `file`; vraća `importId`, brojače i greške preview-a. | `200`, `400`, `401`, `403` |
| `POST /api/orders/import/confirm?importId={UUID}` | Potvrđuje validni preview; vraća `createdCount`. | `200`, `400`, `401`, `403`, `409` |
| `GET /api/orders/{id}/invoice/pdf` | Preuzima informativni PDF narudžbe. | `200`, `401`, `404` |
| `POST /api/reconcile/upload` | Multipart polje `file`; obrađuje izvještaj i vraća trajni batch s redovima. | `201`, `400`, `401`, `403`, `409` |
| `GET /api/reconcile/history` | Lista batch-eva; opcionalno `search`, `from`, `to` (`yyyy-MM-dd`) i `outcome`. | `200`, `400`, `401` |
| `GET /api/reconcile/history/{id}` | Batch i svi trajno zapisani rezultati redova. | `200`, `401`, `404` |
| `GET /api/reconcile/history/{id}/export` | Preuzima XLSX izvoz batch-a. | `200`, `401`, `404` |
| `GET /api/reconcile/history/{id}/export/pdf` | Preuzima PDF izvoz batch-a. | `200`, `401`, `404` |

`outcome` za historiju prihvata `HAS_MATCHES`, `HAS_MISMATCHES`, `HAS_NOT_FOUND`, `HAS_ERRORS`, `PROBLEMS` i `SUCCESSFUL_ONLY`. Datumski raspon je inkluzivan; `from` ne smije biti poslije `to`.

## Testiranje i provjera kvaliteta

```powershell
.\mvnw.cmd clean test
git diff --check
```

Testni profil je u `src/test/resources/application-test.properties`. Koristi H2 memorijsku bazu `jdbc:h2:mem:cod_reconciliation_test`, ima `ddl-auto=create-drop`, a Flyway je u testovima isključen. Zato testovi ne koriste `DB_URL`, niti se povezuju na razvojnu MySQL bazu.

Frontend nema paketni JavaScript alat. Ako je Node.js instaliran, provjera sintakse je:

```powershell
node --check src/main/resources/static/js/app.js
```

## Česti problemi

| Problem | Rješenje |
| --- | --- |
| MySQL nije pokrenut | Pokrenite MySQL servis, zatim izvršite `mysql -u cod_app -p -h localhost cod_reconciliation -e "SELECT 1;"`. |
| Greška DB konekcije | Provjerite naziv baze, korisnika, lozinku, port i `DB_URL`; lokalni start traži eksplicitni TLS `sslMode`. |
| Nedostaje varijabla | U istom PowerShell prozoru postavite sve obavezne `DB_*` i `ADMIN_*` varijable prije starta. |
| Neispravan BCrypt hash | Ponovo pokrenite `generate-bcrypt.ps1` interaktivno i postavite cijeli rezultat s prefiksom `{bcrypt}`. |
| Port 8080 je zauzet | Postavite `$env:SERVER_PORT` na slobodan port prije starta. |
| Upload ne uspijeva | Fajl mora biti `.xlsx`, do 10 MB, imati prvi list i sva tri obavezna zaglavlja. Provjerite pojedinačne redove i ne šaljite isti sadržaj ponovo. |
| Maven build ne uspijeva | Provjerite `java -version`, dostupnost mreže pri prvom preuzimanju zavisnosti i pokrenite `.\mvnw.cmd clean test` iz korijena projekta. |

## Ograničenja i status

Implementirani su upravljanje narudžbama, dvofazni uvoz narudžbi, XLSX reconciliacija, historija, exporti, HTTP Basic/CSRF zaštita i H2 testovi. PDF faktura je informativna: trenutni model ne sadrži prodavača, adresu, stavke artikala ni poreska pravila, pa nije fiskalni dokument. Ručno mapiranje Excel kolona nije implementirano; koriste se samo podržani nazivi zaglavlja. Preview uvoza narudžbi čuva se u memoriji aplikacije deset minuta, pa nije namijenjen dijeljenju između instanci ili restartovanja servera. Početne demonstracijske narudžbe se dodaju svakoj praznoj bazi bez obzira na `APP_ENV`; prije produkcijskog korištenja treba odlučiti da li je to željeno poslovno ponašanje.

Projekt je spreman za narednu fazu pregleda nakon što se lokalno popune varijable okruženja i izvrše testovi iz ovog dokumenta. Ovaj README ne traži brisanje ili resetovanje postojeće baze i migracija.
