# Salvarea aplicației vechi v3, direct de pe tabletă

Acest ajutor citește aplicația **ro.mdp.remix, versionCode 3** dacă a fost construită ca aplicație `debuggable`. Nu o dezinstalează, nu îi șterge datele, nu o oprește forțat și nu scrie în directoarele ei. Dacă Android refuză `run-as`, ajutorul se oprește; nu încerca să ocolești protecția prin resetare sau dezinstalare.

Copia include playlist-urile, favoritele, linkurile și istoricul YouTube, setările, **fișierele audio și fundalul originale**, precum și APK-ul/APK-urile instalate. Produce și o colecție `.mdpremix` pentru funcția **Importă colecția** din aplicația nouă. Cheia privată cu care a fost semnat APK-ul nu se găsește în APK și nu poate fi recuperată prin acest ajutor.

## 1. Pregătirea tabletei

- Păstrează aplicația veche instalată. Nu folosi „Șterge datele”.
- Instalează [Termux de pe F-Droid](https://f-droid.org/packages/com.termux/).
- Pentru documentele care nu pot fi citite automat, instalează și [Termux:API de pe F-Droid](https://f-droid.org/packages/com.termux.api/), din aceeași sursă ca Termux.
- În Termux, copiază comenzile:

```sh
pkg update
pkg install python android-tools ffmpeg termux-api curl
termux-setup-storage
```

Acceptă accesul la fișiere când Android îl cere. Acesta este acces pentru Termux, fără schimbări în aplicația veche. Asigură suficient spațiu liber: copia privată, fișierele audio/fundal și colecția finală pot necesita de peste două ori dimensiunea muzicii. Colecția importabilă are limita de **2 GiB**; dacă o depășești, ajutorul păstrează progresul, dar nu declară o copie completă.

## 2. Depanare wireless Android 11 sau mai nou

Pe Samsung: **Setări → Despre tabletă → Informații software → Număr versiune**, atinge de șapte ori și confirmă PIN-ul. Revino la **Opțiuni pentru dezvoltatori → Depanare wireless**, activează pe o rețea Wi-Fi de încredere.

Deschide **Asociază dispozitivul cu un cod de asociere**. Folosește ecranul împărțit pentru Termux și Setări. În Termux:

```sh
adb pair ADRESA_IP:PORTUL_DE_ASOCIERE
```

Înlocuiește adresa și portul cu cele afișate de Android. Introdu codul doar în solicitarea Termux. Revino la pagina principală „Depanare wireless” și folosește **portul de conectare**, care poate fi diferit:

```sh
adb connect ADRESA_IP:PORTUL_DE_CONECTARE
adb devices
```

Lista trebuie să arate o singură conexiune cu starea `device`. Acest pas depinde de versiunea Android și de politica dispozitivului; nu a fost testat pe tableta ta. Nu publica adresa, codul sau ecranul de asociere în chat.

## 3. Rulează copia

Descarcă ajutorul din ramura proiectului:

```sh
mkdir -p ~/mdp-tools
curl -fL 'https://raw.githubusercontent.com/djmisu-svg/mdp-remix-1and1-nou/feature/premium-settings-share-updates/tools/backup_legacy_tablet.py' -o ~/mdp-tools/backup_legacy_tablet.py
python ~/mdp-tools/backup_legacy_tablet.py
```

În aplicația veche, oprește muzica și revino în Termux înainte de a confirma pornirea. Nu modifica playlist-urile sau setările în timpul copiei. Ajutorul citește din nou datele private la final; dacă s-au schimbat, se oprește și păstrează ambele citiri.

Pentru un audio/fundal pe care Android nu îl permite citit automat, se deschide selectorul Android. Alege **exact fișierul original**, apoi confirmă `DA`. Dacă originalul lipsește, anulează. Un înlocuitor nu ar reprezenta o copie a stării vechi. Raportul precizează care fișiere au fost alese de tine; identitatea lor față de un URI vechi inaccesibil nu poate fi dovedită independent.

## 4. Verifică rezultatul, înainte de orice înlocuire

Succesul afișează `COPIE COMPLETĂ` și calea completă. Directorul se află în `~/mdp-backups/` și conține:

- `MdP-v3-complete.mdpremix`: colecția importabilă, cu media inclusă și verificată SHA-256.
- `legacy-private.tar`: citirea originală a fișierelor private și setărilor, fără conversie.
- `installed-apks/`: copii ale APK-urilor originale.
- `backup-report.json`: rezultatul, sursa fiecărui fișier și sumele de control.
- `COMPLETE.txt`: confirmarea că toate sursele necesare au fost salvate și recitite.

**Fără mesajul de succes și `COMPLETE.txt`, copia este incompletă. Nu dezinstala aplicația veche.** Un raport `INCOMPLETE` identifică ce a lipsit; progresul rămâne în directorul afișat. Nu publica aceste fișiere în repository: conțin date personale și audio.

Păstrează încă o copie a întregului director pe un suport privat sigur. Poți folosi selectorul de fișiere Android al Termux pentru a copia directorul, sau partajează fișierul `.mdpremix` prin Termux:API, înlocuind calea cu cea afișată de ajutor:

```sh
termux-share -a send '/calea/afișată/MdP-v3-complete.mdpremix'
```

Instalează **aplicația nouă separată** și folosește „Importă colecția”. Verifică playlist-urile, favoritele, câteva melodii și GIF-ul, apoi închide/redeschide aplicația nouă. Ajutorul nu pretinde că acest test de restaurare pe Android a fost efectuat. Păstrează originalul instalat până când verificarea ta este completă. Nu încerca înlocuirea pachetului original cu altă cheie înainte de această etapă.

După terminare, dezactivează Depanarea wireless dacă nu mai este necesară.
