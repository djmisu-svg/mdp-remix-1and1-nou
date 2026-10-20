# Distribuire, actualizări și biblioteca brandului

Funcțiile existente ale playerului AKAI sunt păstrate. Această versiune pregătește 1.0.1 / versionCode 4 și păstrează pachetele `ro.mdp.remix` și `ro.mdp.remix.premiumtest` separate.

## Trimit aplicația exact cu colecția mea

În **Setări → Distribuie aplicația**, confirmă trimiterea colecției. Aplicația pregătește două fișiere:

- APK-ul instalat, copiat fără modificare sau resemnare.
- `MdP-RemiX.mdpremix`, cu playlist-uri locale și YouTube, favorite, ordine, muzica locală efectivă, GIF/imagine/video de fundal și preferințele salvate: limba, designul AKAI, încadrarea/întunecarea/animația fundalului, transparența și sincronizarea brandului.

Alege Quick Share, Bluetooth sau o aplicație care poate trimite ambele fișiere și dimensiunea lor. Unele servicii blochează APK-uri sau atașamente mari. Limita colecției este 2 GiB necomprimați; nu se creează în tăcere o copie incompletă dacă un fișier lipsește sau nu mai poate fi citit. Instalările cu APK-uri separate nu sunt prezentate ca un APK unic instalabil.

Destinatarul instalează APK-ul, apoi deschide fișierul `.mdpremix` cu MdP RemiX sau îl alege din **Setări → Importă colecția primită**. Confirmă importul. Melodiile și fundalul devin copii private pe dispozitivul său, independente de fișierele de pe tableta expeditorului. Pe o instalare nouă primește colecția trimisă; pe una existentă playlist-urile și melodiile se adaugă fără ștergerea bibliotecii sale. Aspectul și preferințele vizuale primite se aplică explicit la import. Reimportarea aceluiași pachet păstrează identitățile și nu dublează colecția.

APK-ul singur nu conține datele personale Android. Acestea nu pot fi încorporate dinamic în APK fără a-i schimba semnătura. De aceea se trimit APK-ul și pachetul de date împreună. Linkurile YouTube se transferă ca linkuri; aplicația nu descarcă materialele de pe YouTube.

Arhiva are schemă versionată, limite de dimensiune/număr de fișiere și SHA-256 pentru fiecare fișier. Importul respinge căi periculoase, intrări duplicate, fișiere suplimentare/lipsă și conținut cu sumă greșită înainte de aplicare. La o eroare de stocare în timpul aplicării, biblioteca existentă se păstrează; unele elemente noi pot fi deja adăugate. Reîncercarea aceluiași pachet este repetabilă fără dubluri.

## Actualizare din Setări

**Verifică actualizări** consultă numai GitHub Releases al repository-ului oficial. Compararea folosește `versionCode`, nu ordinea lexicală a numelor versiunilor. O versiune disponibilă arată numărul și modificările; **Descarcă și instalează** arată progresul, verifică APK-ul și deschide instalatorul Android. Android cere confirmarea utilizatorului și, unde este necesar, permisiunea „Instalează aplicații necunoscute”. Nu se face dezinstalare și nu se șterg date.

Înainte de instalare se verifică din nou originea HTTPS a release-ului, metadatele oficiale, dimensiunea, SHA-256, package name, versionCode și certificatul real al APK-ului față de aplicația instalată. Un APK de producție nu se instalează peste Premium Test. O altă semnătură blochează actualizarea și afișează problema.

**Setări → Identitatea aplicației instalate** afișează package name, versionCode și amprenta publică SHA-256. Aceste valori pot fi copiate pentru verificare; nu sunt chei private. Înainte de o actualizare de producție, compară această amprentă cu certificatul cheii originale de release. Dacă diferă, nu publica acea versiune ca actualizare compatibilă și nu dezinstala aplicația ale cărei date trebuie păstrate.

Workflow-ul manual semnat folosește aceeași cheie din secretele GitHub și verifică continuitatea certificatului față de ultimul manifest oficial. Următorul versionCode este cel puțin cel din proiect și strict mai mare decât ultimul publicat. APK-ul semnat, checksum-ul și `update-manifest.json` se publică împreună numai după verificări. Nu există fallback către o semnătură debug. Vezi [pregătirea semnării pe tabletă](ANDROID_RELEASE_SIGNING_TABLET.md).

În lipsa cheii originale și a unui release semnat compatibil, mecanismul este implementat, dar compatibilitatea cu APK-ul instalat pe tableta proprietarului rămâne neverificată. Un test în emulator cu o cheie CI nu dovedește această compatibilitate.

## Transparență și sincronizare

Reglajul **Transparența panourilor** merge de la 0% (opace) la 100% (fundal vizibil prin panouri). Se salvează local și se include în colecția distribuită. Textul, rolele, comenzile și instrumentele rămân vizibile; dimming-ul fundalului rămâne un reglaj separat.

**Biblioteca MdP RemiX** sincronizează biblioteca publică a brandului la cerere și, dacă opțiunea este activă, la revenirea în aplicație cel mult o dată pe oră. Biblioteca personală se păstrează și biblioteca sincronizată rămâne disponibilă offline. Nu trimite automat muzica locală sau fundalul pe internet. Proprietarul publică fișierul exportat prin [ghidul pentru biblioteca brandului](BRAND_LIBRARY.md). Catalogul folosește un release separat, marcat prerelease, pentru a nu fi confundat cu o actualizare APK.

## Verificare

Testele automate includ arhive complete, fișiere WAV/GIF/MP4 reale, permisiuni FileProvider, import fără URI-urile expeditorului, păstrarea bibliotecilor existente, manifestele actualizării, incompatibilități de pachet/semnătură și transparență persistentă. Workflow-ul CI construiește sursa stabilă anterioară și versiunea nouă cu aceeași cheie de test a jobului și verifică actualizarea în loc în emulator, fără dezinstalare, inclusiv UID-ul, JSON-urile, preferințele și octeții fișierelor media. Rezultatele sunt declarate numai după executarea efectivă a workflow-ului. Publicarea producției necesită în continuare cheia originală a proprietarului.
