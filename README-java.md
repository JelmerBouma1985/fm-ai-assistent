# FM Genie 26 Java Rebuild

Spring Boot 3.5 Maven port of the live Linux RAM exporter.

## Build

```bash
mvn -DskipTests package
```

## Export club CSV

Find the FM process:

```bash
pgrep -af fm.exe
```

Export players contracted to, or currently playing for, Feyenoord:

```bash
java -jar target/fm-genie26-rebuild-0.1.0-SNAPSHOT.jar \
  --export-club=Feyenoord \
  --pid=7129 \
  --output=exports/feyenoord_players_java.csv
```

The CSV columns match the Python exporter, including gender, nationality, club, playing club, loan club,
reputation, CA/PA, asking price, contract end date, salary, DOB, age, positions, and visible
attributes.

## REST mode

```bash
mvn spring-boot:run
```

Endpoints:

- `GET /api/processes?query=fm.exe`
- `GET /api/players/club?pid=7129&club=Feyenoord`
- `GET /api/export/club?pid=7129&club=Feyenoord&output=exports/feyenoord_players_java.csv`

## H2 player database

The app uses a file-backed H2 database at `./data/fmgenie26.mv.db`.
Liquibase builds and migrates the schema during application startup. Player loads
use Spring Data JPA repositories to replace the current player snapshot and update
load metadata.

Load all players from live FM RAM into H2:

```bash
curl -X POST 'http://localhost:8080/api/db/players/load?pid=7129'
```

Query loaded players:

```bash
curl 'http://localhost:8080/api/db/players/count'
curl 'http://localhost:8080/api/db/players?name=Lionel%20Messi&limit=5'
curl 'http://localhost:8080/api/db/players?gender=female&limit=5'
curl 'http://localhost:8080/api/db/players?nationality=Argentina&limit=5'
curl 'http://localhost:8080/api/db/metadata'
```

H2 console:

- URL: `http://localhost:8080/h2-console`
- JDBC URL: `jdbc:h2:file:./data/fmgenie26`
- User: `sa`
- Password: empty
