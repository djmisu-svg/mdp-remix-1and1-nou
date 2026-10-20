# Semnarea permanentă Premium, de pe tableta Android

Planul aprobat este o **singură identitate Release permanentă**, pentru aplicația `ro.mdp.remix`. Codul Premium existent este păstrat; sursa pornește de la **1.0.1 / versionCode 4**. Toate actualizările acestei instalări vor folosi aceeași cheie și un versionCode mai mare.

O cheie Release nouă nu poate actualiza aplicația veche semnată cu alt certificat. Este acceptată o instalare nouă după salvarea și verificarea datelor vechi. Nu se dezinstalează automat nimic. **Configurarea cheii nu modifică aplicația veche și poate fi făcută cât timp aceasta rămâne instalată.**

## 1. Păstrează datele vechi

Înainte de orice înlocuire a aplicației, urmează [backupul pe tabletă](../tools/BACKUP_LEGACY_TABLET.md). Copia trebuie să includă playlisturi, favorite, setări, fișiere audio și GIF/imagine/video. Păstrează încă o copie într-un loc privat. Verifică importul în Premium Test, instalat separat, inclusiv redarea și fundalul după repornire. Un fișier de backup creat fără erori nu dovedește că restaurarea pe Android a fost testată.

Dacă backupul, importul sau fișierele originale lipsesc, oprește migrarea și păstrează aplicația veche instalată. Nu folosi „Șterge datele” sau resetarea tabletei. Cheia de semnare și copia datelor personale sunt două backupuri diferite.

## 2. Pregătește Termux

Instalează [Termux din F-Droid](https://f-droid.org/packages/com.termux/). În Termux, copiază:

```sh
pkg update && pkg install openjdk-21 gh curl
termux-setup-storage
```

Acceptă accesul la fișiere cerut de Android. Acesta permite scriptului să salveze **keystore-ul criptat** în Downloads; nu acordă permisiuni suplimentare playerului. Parola va fi păstrată separat, într-un manager de parole.

## 3. Verifică starea și configurează cheia o singură dată

```sh
curl -fL 'https://raw.githubusercontent.com/djmisu-svg/mdp-remix-1and1-nou/feature/premium-settings-share-updates/tools/setup_signing_tablet.sh' -o "$HOME/mdp-signing-setup.sh"
bash "$HOME/mdp-signing-setup.sh" --status
bash "$HOME/mdp-signing-setup.sh"
```

`--status` citește numai prezența fișierelor locale: nu contactează GitHub, nu cere parole și nu creează chei. Absența unei chei locale nu dovedește absența unui backup sau a secretelor GitHub.

La configurare, scriptul cere următoarele acțiuni:

1. **GitHub în browser.** Deschide adresa și introdu codul afișat de `gh`. Conectează-te ca proprietar al repository-ului, apoi revino în Termux. Nu introduce parola GitHub într-un câmp al scriptului.
2. **`NEW`, numai prima dată.** Dacă nu există cheie, backup sau configurare anterioară, scrie exact `NEW` pentru identitatea Release nouă aprobată. Scriptul creează un PKCS12 criptat, cu cheie RSA-4096 și certificat de 10.000 de zile. Acesta va fi reutilizat la fiecare actualizare.
3. **Parola, de două ori.** Alege o parolă de cel puțin 16 caractere, salvată în managerul tău de parole. La tastare nu apar caractere pe ecran; acest lucru este normal.
4. **Backupul privat.** Scriptul salvează `Downloads/MDP-release-signing-backup`. Copiază folderul într-un loc privat sigur, în afara tabletei, și păstrează parola separat. Folderul include keystore-ul criptat și certificatul public. Nu îl încărca în repository, într-un Release, într-un artifact Actions sau într-un mesaj.
5. **`BACKEDUP`.** Scrie exact `BACKEDUP` numai după ce ai salvat backupul și parola. Scriptul încarcă apoi cele cinci secrete prin GitHub CLI, direct din fișier sau din intrarea standard, fără afișarea valorilor.

Nu trimite cheia, parolele sau textul Base64 în conversație. Base64 este o codificare, nu criptare; trebuie protejată ca fișierul original.

Scriptul **nu construiește și nu publică APK-uri**. Ultima comandă afișată pornește numai auditul semnării.

## 4. Dacă execuția este întreruptă

Rulează aceeași comandă din nou:

```sh
bash "$HOME/mdp-signing-setup.sh" --status
bash "$HOME/mdp-signing-setup.sh"
```

- Dacă cheia locală există, este reutilizată cu aceeași parolă; nu este înlocuită.
- Dacă s-a întrerupt încărcarea secretelor, marcajul `upload-pending.sha256` permite reluarea cu aceeași cheie și amprentă.
- Dacă există deja secrete, scriptul oprește generarea și cere auditul; prezența numelor nu dovedește că toate valorile sunt corecte.
- Dacă cheia locală lipsește, dar există un marcaj sau backupul cunoscut, scriptul se oprește înainte de generare. Nu șterge marcajele pentru a forța altă cheie.

Pentru recuperarea backupului Release existent:

```sh
bash "$HOME/mdp-signing-setup.sh" --existing "$HOME/storage/downloads/MDP-release-signing-backup/mdp-remix-release.p12"
```

Introdu aliasul `mdp-remix-release` și parolele acelei chei. `--existing` nu generează o cheie nouă, nici dacă fișierul dispare în timpul configurării. Pentru alt keystore Release valid, folosește locația și aliasul lui reale. Un vechi certificat Android Debug nu este acceptat drept noua identitate publică Release.

Dacă apare o eroare de permisiuni sau conexiune GitHub, scriptul se oprește și păstrează cheia. Mediul GitHub este creat numai după un răspuns 404 confirmat; regulile unui mediu existent sunt păstrate. Nu relua cu altă cheie.

## 5. Confirmă cele cinci secrete

În browser: **repository → Settings → Environments → android-production → Environment secrets**. Scriptul verifică și numele secretelor de repository înainte de a permite o cheie nouă. Proprietarul a confirmat că acestea erau goale la începutul configurării; starea de după rulare trebuie verificată.

| Nume exact | Conținut |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Keystore-ul criptat, codificat Base64 de script |
| `ANDROID_KEYSTORE_PASSWORD` | Parola keystore-ului |
| `ANDROID_KEY_ALIAS` | `mdp-remix-release` pentru cheia nouă |
| `ANDROID_KEY_PASSWORD` | Parola cheii private; la noul PKCS12 este aceeași parolă |
| `ANDROID_SIGNING_CERT_SHA256` | Amprenta SHA-256 a certificatului public, verificată în CI |

Nu trebuie să copiezi manual valorile. GitHub nu permite descărcarea ulterioară a valorilor secretelor; acestea **nu înlocuiesc backupul privat** al keystore-ului și al parolei.

## 6. Rulează auditul, fără build sau publicare

După configurare:

```sh
gh workflow run android.yml --repo djmisu-svg/mdp-remix-1and1-nou --ref feature/premium-settings-share-updates -f target=signing-audit
```

Deschide [Actions](https://github.com/djmisu-svg/mdp-remix-1and1-nou/actions) și citește rezumatul auditului, nu doar culoarea jobului. Auditul verifică existența tuturor secretelor, accesul la cheia privată și potrivirea certificatului cu amprenta configurată; nu generează chei sau APK-uri și nu încarcă artifacte private.

Pentru identitatea Release nouă, `CANDIDATE_MATCH=NONE` este normal: noua cheie nu corespunde vechilor build-uri Debug #47/#48. Trebuie verificate accesul la cheia privată și `CONFIGURED_PIN_MATCH=YES`. Orice secret lipsă, parolă greșită sau nepotrivire a amprentei blochează continuarea. O potrivire cu vechiul APK nu este pretinsă pentru această migrare.

## 7. Verificare și publicare

După audit, build-ul de verificare folosește aceeași cheie din `android-production`, fără fallback Debug. Pachetul rămâne `ro.mdp.remix`, iar workflow-ul alocă un versionCode mai mare decât ultimul manifest oficial publicat. Un certificat diferit de ultimul Release oficial blochează actualizarea.

Publicarea rămâne dezactivată până când sunt confirmate semnătura, backupul privat, funcțiile Premium, instalarea nouă și actualizarea de probă cu aceeași cheie și date păstrate. Distribuirea prin WhatsApp și instalarea pe alt dispozitiv sunt verificări separate, care nu pot fi declarate reușite doar pe baza testelor de emulator. Urmează [procedura finală de verificare și publicare](FINAL_RELEASE_STATUS.md): se publică exact artifactul verificat, fără recompilare sau resemnare.

APK-ul Release verificat va avea un nume și o versiune identificabile. Linkul public stabil pregătit pentru distribuire este:

`https://github.com/djmisu-svg/mdp-remix-1and1-nou/releases/latest/download/MdP-RemiX-Premium.apk`

Acest link **nu este disponibil până la publicarea verificată**. Nu îl prezenta drept descărcare funcțională înainte de existența asset-ului. Utilizatorii primesc APK-ul sau linkul direct; pagina repository-ului și artifactele Actions nu înlocuiesc descărcarea publică.

După instalarea noii versiuni finale, păstrează permanent aceeași cheie, alias, parole și amprentă. Actualizările viitoare folosesc un versionCode mai mare și se instalează peste aceasta. Nu mai genera o identitate nouă pentru o versiune ulterioară.
