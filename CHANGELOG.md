# Endringslogg

## Neste utgivelse

- Versjonsnummeret nedst i appen viser ikkje lenger eit misvisande «-dirty» i F-Droid-bygg.
- Retta artsnavn: enkelte arter hadde feil eller utdatert namn i søket – blant anna mangla
  «svartstrupe» (den låg feilaktig som «afrikasvartstrupe»). Både norske og vitskaplege namn
  hentes nå frå Artsdatabanken, same kjelde som Artsobservasjoner bruker, så dei stemmer med det
  importen godtar (og fleire arter fekk endeleg det svenske namnet sitt med).
- **Arter utanom lista**: finn du ikkje arten i søket, kan du no skrive inn namnet sjølv –
  ei **«Velg …»**-rad dukkar opp nedst. Namnet blir teke med i eksporten akkurat slik du
  skreiv det (så det er opp til deg å treffe namnet importen godtar).
- **Tidspunkt**: ny og ryddigere tid-editor med egne rader for start-tid og sluttid, der
  dato og klokkeslett kan endres hver for seg. Én **Nå**-knapp setter alt til akkurat nå.
  Nytt: hak av **Uten klokkeslett** når du legg inn ein obs i etterkant og ikkje hugsar
  tida – datoen blir med, men klokkeslettet står tomt.
- Betre lokalitetsforslag: står du inne i ein stor lokalitet, blir den foreslått
  framfor ein liten du er utanfor. Er du inne i fleire (ein liten inni ein stor),
  vinn den minste; er du utanfor alle, vinn den du er nærmast kanten av.
- Retta eksport av nye lokaliteter: koordinatene skreiv seg med punktum, som importen
  avviste som «ikke et desimaltall». Nå brukes komma, så egne kartplasseringer importeres.
- **Velg språk på artsnavn**: under Innstillinger (åpne Om-dialogen fra versjonen
  nederst) velg du hovedspråk og sekundærspråk – norsk, svensk eller latin, i begge
  appene. Søket bruker hovedspråket; slå på «søk i begge språk» om du vil kunne
  søke på det andre språket òg (fint når du ikke hugsar navnet på hovedspråket).
- Artsnavna kommer nå fra IOC World Bird List, så feil svenske navn er retta (bl.a.
  «gulsångare» → «härmsångare») og de norske navna har rett skrivemåte.
- I den svenske appen forkortes alder/kjønn i lista nå kompakt («ad ♂»), som i den norske.
- Kartet i den svenske appen tegner lokalitetene slik Artportalen gjør: grå omriss uten
  fyll, med en grønn prikk (vanlig lokalitet) eller grønn trekant (superlokalitet) i midten.
  Tette områder (som Stockholm) blir dermed lesbare i stedet for en grønn klump.
- Svenske artsnavn vises nå med stor forbokstav (som på Artportalen); norske navn er
  fortsatt små, og latinske navn vises alltid i kursiv.
- **Medobservatører**: noter hvem du var ute med på hver observasjon. Følget henger
  igjen fra observasjon til observasjon, så du skriv det inn én gang og slepp å tenke på
  det – og navna du bruker foreslås neste gang. De blir med som egne kolonner i eksporten.
- **Usikker artsbestemming** er tilbake på observasjonssida – huk av når du er usikker
  på bestemminga, så eksporteres den med spørsmålstegn (og «Ja» i usikkerhets-kolonna).
- Nytt utseende når du markerer observasjoner: en ring til venstre på hver rad (og
  på dag-overskriftene, som markerer hele dagen), og felter for antall, Slett og
  Eksporter øverst – uten at lista lenger hopper nedover.
- Du kan nå eksportere bare de observasjonene du har markert.
- Marker flere observasjoner og trykk **Endre** for å rette samme felt – for eksempel
  feil lokalitet – på alle på én gang.
- Hold inne og dra for å markere flere observasjoner i ett drag – lista ruller av
  seg selv når du drar mot toppen eller bunnen.
- Sletter du flere observasjoner på én gang, får du et bekreftelsesspørsmål først.
  Slett-knappene viser også hvor mange du sletter.
- **Nå**-knapp i tid-dialogen setter fra- eller til-tida til akkurat nå med ett trykk.
- Lista ruller opp til den nye observasjonen når du lagrar den, også når den starter
  en ny dag – så du alltid ser det du nettopp la til.
- Større **+**-knapp med romsligere trykkflate, så du lettere treffer den uten å
  bomme på observasjonen bak.
- Om-dialogen krediterer nå kilda til fremmedart-statusen òg.

## v1.0 (16. juli 2026)

- Feltbok er nå ute av beta, etter noen ukers feltbruk. Jippi!

## v0.13 (26. juni 2026)

- Lettere å velge rett lokalitet på kartet.
- Kjønn og alder vises på hovedskjermen.
- Antall arter per dag og totalt vises på hovedskjermen.
- Knapp for å sentrere kartet der du er.
- Små justeringer på artssøk.
- Ymse småforbedringer og feilrettinger.

## v0.12 (20. juni 2026)

- Kartet har nå fornuftige zoom-grenser.
- Appen har fått ny pakke-id for lansering på F-Droid.

## v0.11 (19. juni 2026)

- Tilbake til enkel eksport, ikke kommunevis.
- Observasjonslista er gruppert etter dag igjen.
- Aktivitetsvelger i fullskjerm.
- Noen feilrettinger og forbedringer på antall-feltet.
- Angre-funksjon heller enn bekreftelse ved sletting.
- Artssøket er marginalt bedre.
- Appen tar mindre plass.
- Vesentlig enklere å treffe riktig lokalitet på kartet.
- Lokalitetsnavn på kartet roter seg ikke lenger oppå hverandre.
- Bedre plassering av lokalitetsnavn på kartet.
- Superlokaliteter vises i mørkere grønnfarge.
- GPS-prikken er større.
- Lokaliteter dekker ikke for hverandre i kartet.
- Kjappere og bedre kopier-funksjon.
- Oppdaterte lokaliteter og superlokaliteter inkludert Svalbard.
- Ymse småforbedringer.

## v0.10 (10. juni 2026)

- Bedre forhåndsvalg i artssøket før du begynner å skrive.
- Du kan kopiere observasjoner.
- Kommunevis eksport, for å unngå kolliderende lokalitetsnavn.
- Indiker hvilke observasjoner som har kommentar.
- _Columba livia_ heter nå «bydue», ikke «klippedue», slik Artsobservasjoner befaler.
- Ymse småforbedringer og feilrettinger.

## v0.9 (8. juni 2026)

- Forbedra artssøk, burde finne riktig fugl ganske lett.
- Vis/skjul private lokaliteter i kartet.
- Observasjoner grupperes per dag.
- Automatisk sikkerhetskopi til Nedlastinger.
- Bekreftelse før du sletter en observasjon.
- Retta en krasj som kunne gjøre observasjonene utilgjengelige.
- Retta at en nylaga lokalitet kunne bli umulig å velge igjen.
- Enklere og ryddigere redigering av tidspunkt på en observasjon.
- Ymse småforbedringer og feilrettinger.

## v0.8 (6. juni 2026)

- Marker og slett flere observasjoner samtidig.
- Fremmedartstatus vises ved siden av rødlistestatus.
- Bekreftelse før du forkaster en observasjon.
- Nytt, tåpelig appikon. Ser du hvilken fugl det er?
- Ymse småforbedringer og feilrettinger.

## v0.7 (5. juni 2026) og før

- Den spede begynnelse.
