# Installere Feltbok

Feltbok ligger ikke i Google Play ennå, så du installerer den direkte fra GitHub.
Det tar under et minutt. Underveis viser Android en advarsel fra **Google Play
Protect** – det er helt normalt for apper utenfor Play-butikken og betyr ikke at
noe er galt.

1. Åpne [siste utgivelse](https://github.com/mortenfyhn/feltbok/releases/latest)
   og last ned `.apk`-fila (den ligger under **Assets**).
2. Trykk på fila når den er lastet ned. Første gang spør Android om nettleseren får
   «installere ukjente apper» – si ja og følg meldingen.
3. Play Protect sier **«Appen er blokkert for å beskytte enheten din»** fordi den
   ikke har sett apper fra denne utvikleren før. Trykk **Flere detaljer**.

   ![Play Protect blokkerer appen](img/play-protect-blokkert.jpg)

4. Trykk **Installer likevel**.

   ![Installer likevel](img/play-protect-installer-likevel.jpg)

5. Ferdig! Feltbok dukker opp i app-skuffen.

## Hvorfor advarselen?

Feltbok er åpen kildekode (koden ligger her i repoet) og krever bare posisjon for
å finne nærmeste lokalitet. Advarselen kommer av *hvordan* appen installeres –
utenom Play-butikken – ikke av hva appen gjør. Når appen etter hvert ligger i
Google Play, forsvinner advarselen.
