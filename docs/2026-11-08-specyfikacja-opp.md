# Odcinkowy Pomiar Prędkości – asystent OPP

Specyfikacja ustalona 2026-11-08. Aplikacja na Androida, tylko do użytku własnego (APK instalowany ręcznie).

## Cel

Kierowca widzi na telefonie małą, przesuwalną wyspę, która:
- informuje o zbliżaniu się do odcinka OPP i o wjeździe na niego,
- pokazuje limit odcinka oraz maksymalną prędkość, z jaką można jechać przez resztę odcinka, żeby średnia nie przekroczyła limitu,
- pokazuje postęp przejazdu odcinka.

## Platforma

- Natywny Kotlin, minSdk 26 (Android 8.0), targetSdk aktualny.
- Dystrybucja: APK budowany przez GitHub Actions, instalacja ręczna. Bez Google Play.
- Uprawnienia: lokalizacja (w tym w tle), wyświetlanie nad innymi aplikacjami (`SYSTEM_ALERT_WINDOW`), Bluetooth (`BLUETOOTH_CONNECT`), powiadomienia, foreground service typu `location`.

## Dane o odcinkach

- Faza 1: stały plik `data/odcinki.json` w repozytorium, wygenerowany z OpenStreetMap (relacje `enforcement=average_speed`, trasy z OSRM) skryptem `tools/2026-11-08-buduj-odcinki.py`.
  - Aplikacja ma wbudowaną kopię (assets) – działa offline od pierwszego uruchomienia.
  - Raz w tygodniu (lub przy pierwszym starcie po upływie 7 dni) pobiera aktualny plik z `raw.githubusercontent.com` i zapisuje lokalnie. Błąd pobierania = zostaje poprzednia wersja.
- Faza 2: logika po stronie serwera okresowo aktualizująca plik w repo (źródła: GITD/CANARD, OSM). Poza zakresem fazy 1.

### Format odcinka

Każdy odcinek jest kierunkowy (dwa kierunki = dwa wpisy).

```json
{
  "wersja": 1,
  "aktualizacja": "2026-11-08",
  "odcinki": [
    {
      "id": "osm-6694778",
      "nazwa": "19 PNW.O.2.001",
      "droga": "A1",
      "limit": 140,
      "dlugosc_m": 7400,
      "trasa": [[52.53, 19.15], [52.56, 19.12], [52.58, 19.10]]
    }
  ]
}
```

- `trasa` – polilinia od punktu pomiaru wjazdowego do wyjazdowego (lat, lon), w kierunku jazdy.
- `dlugosc_m` – oficjalna długość, jeśli znana; w przeciwnym razie liczona z polilinii.

## Start i zatrzymanie

- Użytkownik wybiera w ustawieniach jedno lub więcej sparowanych urządzeń Bluetooth auta.
- Połączenie z wybranym urządzeniem → start usługi w tle (foreground service + powiadomienie).
- Rozłączenie → usługa działa jeszcze 2 minuty (np. postój na stacji), potem się wyłącza.
- Możliwy też ręczny start/stop z aplikacji.

## Wykrywanie odcinka

- Pozycja GPS co ~1 s podczas działania usługi.
- **Zapowiedź**: w odległości ≤550 m przed punktem wjazdowym (po prostej), gdy jedziemy w jego stronę (±50°) i w kierunku odcinka (±90°), wyspa pokazuje „za X m”, limit; zdarzenie raz na przejazd.
- **Wjazd**: przejechanie punktu wjazdowego (rzut pozycji na polilinię przechodzi przez 0 m, kierunek jazdy zgodny z kierunkiem odcinka ±45°). Od tej chwili liczy się czas.
- **Kierunek**: rozpoznawany po kolejności rzutów na polilinię i kursie GPS. Odcinek przeciwnego kierunku jest ignorowany.
- **Zjazd**: przejechanie punktu końcowego lub oddalenie się od polilinii o >150 m przez >20 s (zjazd z trasy).
- **Wjazd niezłapany** (aplikacja wystartowała w trakcie odcinka, brak GPS przy wjeździe): wyspa pokazuje sam limit i komunikat „Brak danych od wjazdu – trzymaj limit”. Bez wyliczonej prędkości.

## Obliczenia

- `t_min = dlugosc / (limit − zapas)` – minimalny czas przejazdu.
- `t_uplyw` – czas od wjazdu.
- `d_pozostaly` – dystans od bieżącego rzutu na polilinię do końca.
- **Prędkość dozwolona** = `d_pozostaly / (t_min − t_uplyw)`.
  - Jeśli `t_min − t_uplyw ≤ 0` – minął minimalny czas przejazdu, więc średnia na pewno zmieści się w limicie (np. po korku): dozwolona = maksimum (199), kolor niebieski. Stanu „średnia przekroczona” nie ma – zawsze można zwolnić.
- **Zapas** – ustawiany w opcjach, domyślnie 2 km/h.
- Wartość może być wyższa niż limit – wtedy jest pokazywana na niebiesko (ostrzeżenie: chwilowy limit nadal obowiązuje).

### Utrata GPS

- Czas liczy zegar systemowy, więc się nie gubi.
- Pozycja szacowana na podstawie ostatniej znanej prędkości (dead reckoning wzdłuż polilinii).
- Wartość w tym czasie jest szara (przybliżona); po powrocie sygnału korekta do rzeczywistej pozycji.

## Wyspa (nakładka)

- Pływający element nad innymi aplikacjami, przesuwany palcem, pozycja zapamiętywana.

### Stany

1. **Czuwanie** (poza odcinkiem): mała szara kropka. Brak GPS – kropka z przekreśleniem.
2. **Zapowiedź** (~500 m przed): znak z limitem + napis „za 500 m”.
3. **Na odcinku**:
   - po lewej: znak ograniczenia prędkości (białe koło, czerwona obwódka, czarna liczba) z limitem odcinka,
   - po prawej: maksymalna dozwolona prędkość do końca odcinka,
   - wokół liczby dozwolonej: pierścień postępu – zapełnia się proporcjonalnie do przejechanej części odcinka.
4. **Podsumowanie** (po zjeździe): średnia z odcinka, znika po 10 s.

Aktualna prędkość **nie jest** wyświetlana (kierowca ma ją na zegarach).

### Kolory liczby dozwolonej / pierścienia

Porównanie: aktualna prędkość vs wyliczona prędkość dozwolona.

| Kolor | Warunek |
|---|---|
| zielony | aktualna ≤ dozwolona |
| żółty | aktualna przekracza dozwoloną o 1–5 km/h |
| czerwony | aktualna przekracza dozwoloną o >5 km/h (powtarzany sygnał) |
| niebieski | dozwolona > limit odcinka (nadpisuje zielony) |
| szary | wartość szacowana (brak GPS) |

## Powiadomienia dźwiękowe

Tryb wybierany w opcjach:
- **Sygnał** (domyślny): krótki dźwięk przy zapowiedzi/wjeździe, powtarzany przy kolorze czerwonym (nie częściej niż co 10 s), inny dźwięk przy zjeździe.
- **Głos** (TTS, polski): „Odcinkowy pomiar, limit 70, 4 kilometry”, „Zwolnij, maksymalnie 64”, „Koniec odcinka, średnia 68”.
- **Cichy**: tylko wyspa.

## Ustawienia

- Urządzenia Bluetooth auta (wybór z listy sparowanych).
- Zapas prędkości (km/h, domyślnie 2).
- Tryb powiadomień (sygnał / głos / cichy).
- Ręczne „Sprawdź aktualizację odcinków” + data ostatniej aktualizacji.

## Poza zakresem fazy 1

- Wyświetlanie na ekranie auta (Android Auto).
- Automatyczna aktualizacja pliku odcinków po stronie serwera.
- Publikacja w Google Play.
