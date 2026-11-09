# Odcinkowy Pomiar Prędkości – instalacja i użycie

## Pobranie APK

- **Z gałęzi roboczej:** GitHub → zakładka *Actions* → ostatni przebieg „Build APK” → sekcja *Artifacts* → `odcinkowy-pomiar-apk` (zip z `odcinkowy-pomiar.apk`). Wymaga zalogowania.
- **Z `master`:** GitHub → *Releases* → „latest” → `odcinkowy-pomiar.apk` (można pobrać prosto na telefon).

APK jest podpisany stałym kluczem (`app/matka.keystore`), więc kolejne wersje instalują się jako aktualizacja – bez odinstalowywania.

## Instalacja

1. Otwórz pobrany `odcinkowy-pomiar.apk` na telefonie, zezwól przeglądarce/menedżerowi plików na instalację z nieznanych źródeł.
2. Uruchom „Odcinkowy Pomiar Prędkości” i w sekcji **Uprawnienia** nadaj wszystko, co ma ✗:
   - Lokalizacja (dokładna),
   - Lokalizacja w tle – w ustawieniach wybierz **„Zezwalaj zawsze”**,
   - Wyświetlanie nad innymi aplikacjami,
   - Bluetooth, Powiadomienia,
   - Bateria bez ograniczeń – bez tego Android może zablokować automatyczny start po połączeniu z autem.
3. W sekcji **Samochód (Bluetooth)** zaznacz urządzenie auta (musi być wcześniej sparowane).
4. Ustaw zapas prędkości (domyślnie 2 km/h) i tryb dźwięku (domyślnie sygnał). „Test dźwięku” sprawdza głośność.

## Jak działa

- Po połączeniu z Bluetoothem auta startuje usługa (powiadomienie „Czuwam – N odcinków”). Po rozłączeniu wyłącza się po 2 minutach. Można też uruchomić ręcznie przyciskiem „Uruchom”.
- **Szara kropka** – aplikacja czuwa i ma GPS. Kropka z czerwonym przekreśleniem – brak GPS.
- **~500 m przed odcinkiem** – znak z limitem i „za X m”, krótki sygnał.
- **Na odcinku** – po lewej znak z limitem, po prawej maksymalna prędkość, z jaką możesz jechać do końca odcinka. Pierścień wokół liczby pokazuje przejechaną część odcinka.
  - zielony – jedziesz nie szybciej niż dozwolona,
  - żółty – szybciej o 1–5 km/h,
  - czerwony – szybciej o >5 km/h (sygnał co 10 s, głos co 30 s),
  - niebieski – dozwolona jest wyższa niż limit (chwilowy limit nadal obowiązuje!),
  - szary „≈ GPS” – brak sygnału, wartość szacowana z ostatniej prędkości.
- Jeśli aplikacja nie złapała wjazdu (np. wystartowała w trakcie odcinka) – „Brak danych od wjazdu – trzymaj limit”.
- Po zjeździe – podsumowanie ze średnią przez 10 s.
- Wyspę przesuwasz palcem; pozycja jest zapamiętywana. Dotknięcie otwiera ustawienia.
- **Fotoradary** – ~15 s przed stałym fotoradarem (300–800 m, zależnie od prędkości) sygnał: trzy krótkie niskie tony i jeden dłuższy wyższy. W trybie głosowym „Fotoradar za X metrów, limit Y”. Ostrzega tylko, gdy fotoradar jest przed autem i (jeśli znany) mierzy Twój kierunek jazdy. Wyłączysz w ustawieniach.
- Kontroli policyjnych i zgłoszeń z Google Maps / Waze / Android Auto aplikacja **nie** dostaje – te serwisy nie udostępniają ich innym aplikacjom.

## Tryb demo

Ustawienia → „Uruchom demo”. Symulowany przejazd (bez auta, bez GPS, czas ×3, ok. 1,5 min): fotoradar, zapowiedź odcinka, spokojna jazda, przekroczenie (czerwony), odrabianie średniej (niebieski), utrata GPS (szary), podsumowanie. Wyspa i dźwięki działają jak w prawdziwej jeździe; pod przyciskiem widać aktualną symulowaną prędkość i stan. Można wyjść do ekranu głównego – demo leci dalej nad innymi aplikacjami. Wymaga tylko uprawnienia do nakładki.

## Dane odcinków

- `data/odcinki.json` – 229 kierunków odcinków z OpenStreetMap (relacje `enforcement=average_speed`), trasy z OSRM.
- Kopia jest wbudowana w APK. Raz w tygodniu (przy starcie usługi) aplikacja pobiera aktualny plik z
  `https://raw.githubusercontent.com/konrad44/odcinkowy-pomiar/master/data/odcinki.json` – działa od razu (repo `odcinkowy-pomiar`, gałąź `master`).
- Regeneracja danych: `python3 tools/2026-11-08-buduj-odcinki.py` w pustym katalogu, wynik skopiować do `data/`.
- `data/fotoradary.json` – stałe fotoradary z OSM (`highway=speed_camera`), regeneracja: `python tools/2026-11-09-buduj-fotoradary.py` z katalogu repo. Aktualizowane w aplikacji razem z odcinkami.
- Dane OSM mogą być niekompletne lub nieaktualne – nowy odcinek dopisujesz do JSON-a (format w specyfikacji).

## Budowanie lokalnie

```
./gradlew testDebugUnitTest assembleRelease
```
Wynik: `app/build/outputs/apk/release/app-release.apk`. Wymaga JDK 17+ i Android SDK (platforma 36).
