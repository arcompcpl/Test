# AiTrackerEye Konfigurator - Android (v0.1)

Aplikacja na telefon do wstępnej konfiguracji kamer IP **BCS / Dahua** przez Wi-Fi (HTTP CGI + podgląd RTSP). Funkcje jak w wersji na Windows: odczyt ustawień, tryb i długość nagrań na kartę SD, harmonogram tygodniowy, FPS / bitrate / kodek / GOP, synchronizacja czasu, podgląd na żywo, diagnostyka.

> **Wersja 0.1, niesprawdzona na prawdziwej kamerze.** Zależnie od firmware część funkcji może być niedostępna. Przed zapisem aplikacja robi kopię konfiguracji. Hasło kamery nie jest zapamiętywane.

## Jak dostać plik APK (bez Android Studio)

1. **Konto GitHub:** wejdź na https://github.com/signup i załóż darmowe konto (e-mail, hasło, nazwa użytkownika, potwierdzenie z e-maila).
2. **Nowe repozytorium:** po zalogowaniu kliknij **+** (prawy górny róg) → **New repository**. Nazwa np. `AiTrackerEye-Android`, zaznacz **Private**, nic więcej nie zaznaczaj → **Create repository**.
3. **Wgraj pliki:** rozpakuj ZIP na komputerze. Na stronie nowego repozytorium kliknij **uploading an existing file**, a potem przeciągnij tam **całą zawartość** rozpakowanego folderu (foldery `app` i `.github` oraz pliki `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` itd.), tak żeby `settings.gradle.kts` był na pierwszym poziomie repozytorium. Kliknij **Commit changes**.
   - Jeśli folder `.github` się nie wgrał: **Add file → Create new file**, w polu nazwy wpisz `.github/workflows/build-apk.yml` i wklej zawartość pliku o tej nazwie z ZIP-a.
4. **Budowanie:** otwórz zakładkę **Actions**. Jeśli GitHub poprosi o włączenie, zatwierdź. Workflow **Zbuduj APK** uruchomi się sam po wgraniu plików (albo kliknij **Run workflow**). Pierwsze budowanie trwa kilka minut. Zielony ptaszek = gotowe.
5. **Pobranie:** kliknij zakończone uruchomienie → na dole w sekcji **Artifacts** pobierz **AiTrackerEye-APK** (ZIP z plikiem `app-debug.apk`).
6. **Instalacja:** przenieś `app-debug.apk` na telefon i otwórz go. Android poprosi o zgodę na instalację z nieznanych źródeł. Play Protect może ostrzec o nieznanym twórcy: wybierz „Zainstaluj mimo to”.

Jeśli budowanie kończy się czerwonym X, wejdź w to uruchomienie → zadanie **build**, skopiuj ostatnie ~50 linii logu i wyślij je do Claude'a, a kod zostanie poprawiony.

## Użycie

Telefon musi być w **tej samej sieci Wi-Fi** co kamera (np. oba w `192.168.1.x`). Wpisz IP kamery, login i hasło → **Połącz i odczytaj**.

Aplikacja używa zwykłego HTTP (kamery lokalne zwykle nie mają HTTPS), więc przy HTTP hasło idzie po sieci bez szyfrowania. Używaj tylko zaufanej sieci.

## Dla programistów

Kotlin + Jetpack Compose, OkHttp (własna obsługa HTTP Digest), Media3 (RTSP). Czysta logika bez zależności od Androida jest w `Logic.kt`.
