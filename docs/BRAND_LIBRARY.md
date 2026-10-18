# Biblioteca online comună MdP RemiX

Aplicația citește biblioteca publică din repository-ul oficial `djmisu-svg/mdp-remix-1and1-nou`, prerelease-ul separat **brand-library**, fișierul **brand-library.json**. Acesta este un catalog de linkuri YouTube și playlist-uri, cu ordinea și favoritele publicate de brand. Redarea YouTube continuă prin metodele oficiale existente. Nu se descarcă melodii de pe YouTube.

Biblioteca comună este distinctă de transferul personal complet: sincronizarea publică nu încarcă fișiere audio locale, GIF-uri, fundaluri, istoricul audițiilor, parole sau date private. Aplicația nu conține un token GitHub și nu poate publica în numele proprietarului.

## Pentru utilizatori

În Setări → **Biblioteca MdP RemiX**, folosește **Sincronizează acum**. Sincronizarea automată verifică la pornire/revenire în prim-plan, cel mult o dată pe oră; poate fi oprită din același panou. Dacă proprietarul nu a publicat încă fișierul, aplicația afișează acest lucru și păstrează biblioteca existentă.

Datele sunt salvate local și rămân disponibile offline. Reviziile repetate nu sunt importate din nou. Actualizarea înlocuiește numai elementele administrate de brand; păstrează biblioteca personală și linkurile de brand folosite în playlist-uri personale. O eroare de rețea sau un fișier invalid nu șterge colecția existentă.

## Publicare de pe tableta Android a proprietarului

1. În aplicație, în Setări → Biblioteca MdP RemiX, apasă **Exportă biblioteca personală pentru publicare**. Salvează `brand-library.json` în **Downloads**. Exportul conține playlist-urile personale și linkurile YouTube; verifică să dorești publicarea titlurilor și playlist-urilor respective. Copiile administrate de brand sunt omise pentru a nu fi republicate în dublu exemplar. Linkurile de brand folosite în playlist-uri personale sunt păstrate în export. Dacă lucrezi pe alt dispozitiv, creează playlist-uri personale pentru selecția pe care dorești să o publici. Exportul nu publică nimic automat.
2. Deschide [Termux din F-Droid](https://f-droid.org/packages/com.termux/), apoi rulează:

```sh
pkg install gh python curl
termux-setup-storage
```

3. Permite accesul cerut de Termux pentru a citi exportul din Downloads. Descarcă utilitarul oficial:

```sh
curl --fail --location --output publish_brand_library_tablet.sh \
  'https://raw.githubusercontent.com/djmisu-svg/mdp-remix-1and1-nou/feature/premium-settings-share-updates/tools/publish_brand_library_tablet.sh'
bash publish_brand_library_tablet.sh "$HOME/storage/downloads/brand-library.json"
```

4. Scrie `PUBLICA` după ce ai verificat că această colecție poate fi publică. Dacă este necesar, utilitarul deschide autentificarea GitHub în browser. Conectează contul proprietarului cu drept de publicare în repository. Nu introduce un token sau o parolă în cod ori în chat.
5. Utilitarul creează o singură dată prerelease-ul **brand-library**, fără a-l marca drept ultima versiune Android, apoi încarcă/actualizează doar `brand-library.json`. Pe orice dispozitiv, **Sincronizează acum** preia noua revizie.

Pentru schimbări ulterioare, exportă din nou din aplicație și rulează din nou ultima comandă. Fiecare export are un identificator de revizie nou. Dacă managerul de fișiere a adăugat `(1)` în numele exportului, folosește în comandă numele real, între ghilimele.

**Nu transforma `brand-library` într-un release stabil.** Acest prerelease este catalogul comun, nu o actualizare APK; sistemul de actualizări Android trebuie să îl ignore. Nu încărca arhiva de transfer personal complet în acest prerelease public.

## Format și verificări

JSON-ul are `schemaVersion: 1`, `revision` UUID și `library` cu schema existentă a linkurilor. Limita este 1 MiB. Clientul acceptă doar endpoint-ul repository-ului oficial, tag-ul și numele de asset exacte, HTTPS și redirecționările CDN GitHub pentru asset-uri. Dacă GitHub furnizează digestul SHA-256 al asset-ului, acesta este verificat înainte de import. Versiunile incompatibile, JSON-ul invalid și erorile de descărcare nu modifică datele locale.

Publicarea inițială necesită un export real al proprietarului și autentificarea sa GitHub; implementarea nu inventează playlist-uri și nu publică date în mod automat.
