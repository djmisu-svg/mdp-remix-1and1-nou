# Descărcarea APK-ului

Conectarea GitHub a fost rezolvată. Proiectul complet este publicat pe branch-ul **feat/mdp-remix-youtube-player**, în [Pull Request #1](https://github.com/djmisu-svg/mdp-remix-1and1-nou/pull/1). Nu mai trebuie să încarci sau să editezi codul manual.

1. Deschide [rulările Actions ale branch-ului](https://github.com/djmisu-svg/mdp-remix-1and1-nou/actions?query=branch%3Afeat%2Fmdp-remix-youtube-player).
2. Alege o rulare **Android Debug APK** care a trecut complet, cu rezultat verde.
3. În **Artifacts**, descarcă **MdP-RemiX-debug** din contul tău GitHub.
4. Dezarhivează și instalează **MdP-RemiX.apk** pe tabletă. Android minim: 6.0/API 23.

Workflow-ul folosește JDK 17, Gradle 8.9, platform-tools, platforms;android-35 și build-tools;35.0.0; nu cere vechiul pachet SDK tools. Include 60 de teste JVM, lint, build, verificarea semnăturii APK-ului și checksum SHA-256. Artifactele expiră după 30 de zile.

Proiectul și ZIP-ul anterior au rămas separat în workspace. Build-ul este verificat efectiv în Actions; instalarea și redarea pe dispozitiv Samsung trebuie verificate după descărcare. Branch-ul este separat de main și poate fi revizuit în Pull Request.

Pentru a publica sursele în alt repository, instrucțiunile alternative sunt în [START_HERE.md](START_HERE.md).
