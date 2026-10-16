# Verificări

## Verificări disponibile fără SDK

```sh
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/verify_project.py
```

Testele Python verifică protecțiile configurației CI: refuzul vechiului pachet SDK tools, versiunea JDK, integritatea wrapper-ului, coerența versiunilor pluginurilor și permisiunea serviciului media. Acestea nu execută logica aplicației Android. Verificatorul inspectează structura, XML-urile/resursele, wrapper-ul oficial, versiunile, callback-urile necesare și configurația workflow-ului fără instalări sau acces la rețea.

## Automat

`bash ./gradlew testDebugUnitTest lintDebug assembleDebug testPremiumTestUnitTest lintPremiumTest assemblePremiumTest`

Testele sunt în `app/src/test/java/ro/mdp/remix/data` și `app/src/test/java/ro/mdp/remix/links`. JSON este testat cu implementarea reală `org.json`, nu cu stub-urile Android. Testele includ validarea URL-urilor YouTube oficiale, canonizarea linkurilor, refuzul domeniilor similare și al adreselor invalide, CRUD, reordonarea, favoritele, istoricul și persistența JSON. Lint blochează erorile. CI verifică semnăturile, identificatorii și numele ambelor APK-uri, apoi instalarea simultană și izolarea datelor într-un emulator Android 35. Artifactul **MdP-RemiX-premiumtest** conține numai **MdP-RemiX-Premium-Test.apk**, cu identificatorul `ro.mdp.remix.premiumtest`, și checksum-ul SHA-256. Aplicația existentă `ro.mdp.remix` rămâne instalată, cu datele ei. Detalii: [PREMIUM_TEST.md](PREMIUM_TEST.md).

## Pe o tabletă sau un telefon Android

Aceste verificări necesită dispozitiv/emulator cu redare audio și nu sunt substituite de compilare:

- Instalează APK-ul; verifică numele, sigla, lansarea fără cont și interfața în portret/peisaj, split screen și pe un telefon.
- În YouTube & linkuri, salvează un link de playlist oficial și un videoclip/youtu.be/Shorts cu titluri proprii. Verifică refuzul unui link invalid și eliminarea duplicatelor când același clip este introdus cu alt format al URL-ului.
- Creează, redenumește, reordonează și șterge playlisturi de linkuri. Atașează/detașează linkuri, schimbă ordinea lor și verifică păstrarea ordinii după redeschiderea aplicației.
- Marchează atât linkuri cât și playlisturi favorite; verifică secțiunea Favorite. Caută după titlu, URL și numele playlistului. Deschide o colecție și un link, apoi verifică Recente.
- Cu YouTube instalat, linkul trebuie să deschidă aplicația oficială. Fără YouTube, trebuie să deschidă un browser. Verifică mesajul dacă nu există niciun handler. Istoricul înregistrează predarea către handler, nu confirmă redarea efectivă a videoclipului.
- Redeschide aplicația în modul avion: linkurile, favoritele, playlisturile și istoricul trebuie să fie disponibile local; redarea YouTube cere internet în aplicația externă.
- Importă mai multe fișiere audio locale; verifică metadatele, gruparea albumelor și faptul că reimportul nu creează duplicate. Anulează selectorul și încearcă un document invalid: aplicația trebuie să rămână utilizabilă.
- Verifică Play/Pause, Previous/Next, începutul/sfârșitul cozii, bara de progres și volumul, inclusiv o melodie cu durată scurtă.
- Marchează favorite, creează/șterge playlisturi, adaugă/scoate melodii și elimină o melodie din bibliotecă. Închide și redeschide aplicația: biblioteca/favoritele/playlisturile trebuie păstrate, iar fișierul original trebuie să existe.
- Redenumește playlisturile audio locale și schimbă ordinea playlisturilor/melodiilor din ele. Verifică limitele sus/jos și persistența după restart.
- Activează modul avion, blochează ecranul, așteaptă schimbarea piesei și verifică comenzile de pe ecranul blocat/notificare. Glisează aplicația din Recents în timpul redării: muzica trebuie să continue.
- Verifică pauza la deconectarea căștilor și comportamentul la apel/altă aplicație audio. Verifică pauza/continuarea din căști Bluetooth.
- Șterge/mută un fișier importat, apoi încearcă redarea: aplicația trebuie să afișeze o eroare și să permită selectarea altei melodii.
- Rotește ecranul în timpul redării/importului și verifică faptul că nu se dublează playerul sau importul. Verifică font mărit și TalkBack.

Nu marca verificările manuale ca trecute fără executarea lor pe un dispozitiv.
