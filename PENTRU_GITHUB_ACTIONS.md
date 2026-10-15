# Încărcarea proiectului și descărcarea APK-ului

Arhiva nouă este **MdP-RemiX-project.zip**, iar folderul ei principal este **mdp-remix**. Codul este pregătit complet; nu trebuie editat manual și nu necesită cheie API.

1. Dezarhivează și încarcă **conținutul** folderului mdp-remix în rădăcina repository-ului djmisu-svg/mdp-remix-1and1-nou, inclusiv folderul ascuns **.github**. Acceptă înlocuirea fișierelor cu aceleași nume, păstrând directoarele. Nu încărca doar ZIP-ul.
2. **build.gradle** și **settings.gradle** de la rădăcină înlocuiesc configurația veche defectă. Copiile .kts sunt păstrate. Modulul app folosește app/build.gradle.kts.
3. În GitHub → **Actions**, deschide rularea **Android Debug APK** pentru commit-ul încărcat și așteaptă rezultatul verde.
4. În **Artifacts**, descarcă **MdP-RemiX-debug** din contul tău GitHub. Dezarhivează și instalează **MdP-RemiX.apk** pe tabletă. Android minim: 6.0/API 23.

Workflow-ul folosește JDK 17, platform-tools, platforms;android-35 și build-tools;35.0.0; nu cere vechiul pachet SDK tools. Include teste JVM, lint, build, verificarea semnăturii APK-ului și checksum SHA-256. Artifactele expiră după 30 de zile.

Proiectul și ZIP-ul anterior au rămas separat în workspace. Compilarea Android, testele JVM și instalarea pe dispozitiv nu sunt confirmate aici. Nu a fost încărcat un APK și nu a fost reluată integrarea GitHub care a refuzat scrierea cu 403.

Instrucțiuni complete: [START_HERE.md](START_HERE.md). Verificări și limite: [docs/VERIFICARE_LIVRARE.md](docs/VERIFICARE_LIVRARE.md).
