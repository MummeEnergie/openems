# Peak-Shaving mit externen REST-Batteriesollwerten

Diese Anleitung beschreibt den optionalen Begrenzungsmodus von
`Controller.Symmetric.PeakShaving` und einen lokalen Ersatzbetrieb mit
`Controller.Symmetric.Balancing`. Sie ist eine Konfigurationsvorlage, keine
Freigabe oder Bestandsaufnahme der RevPi-Anlage. Es wurden keine Live-Stellversuche
oder Änderungen an laufenden Anlagen vorgenommen.

## Verhalten und Priorität

`limitOnly=false` bleibt der Standard: bisherige aktive Peak-Shaving-/Rückladeregelung
mit PID und Blindleistung 0 var. Für die REST-Anbindung ausdrücklich
`limitOnly=true` wählen. Dann gilt je Zyklus:

```text
Standortbedarf ohne Batterie = GridMeter.ActivePower + ESS.ActivePower
ESS-Untergrenze = Standortbedarf ohne Batterie - peakShavingPower
```

Positive ESS-Werte entladen, negative laden; alle Werte sind Watt. Die Untergrenze
bleibt auch dann aktiv, wenn sie negativ ist. Weder eine Null-Gleichheitsvorgabe
noch eigenständiges Rückladen oder eine Blindleistungsvorgabe werden gesetzt.
`rechargePower` ist im Begrenzungsmodus ohne Wirkung. Der Netzgrenzwert ist eine
Momentanleistungsgrenze; Viertelstundenmittel und wirtschaftlicher Fahrplan sind
separat abzustimmen.

Hardwaregrenzen und bereits gesetzte Schutz-Constraints haben Vorrang. Zusätzlich
verhindert der Begrenzungsmodus bei `SoC <= socInfimum` Entladung und bei
`SoC >= socSupremum` Ladung, jeweils mit einer gerichteten Ungleichung. Die zulässige
Gegenrichtung bleibt frei, soweit die Netzgrenze sie erlaubt. Die Grenzen müssen
`0 <= socInfimum < socSupremum <= 100` erfüllen. Frühere Schutzvorgaben können auch
diese Betriebsgrenzen überstimmen, beispielsweise eine notwendige Zwangsladung.
Der alte vollständige Ausstieg außerhalb des SoC-Fensters bleibt allein im
Standardmodus erhalten.

`LimitUnfulfillable` meldet nach fünf aufeinanderfolgenden Zyklen, dass die
benötigte ESS-Untergrenze oberhalb der verbleibenden Maximalleistung liegt. Es
wird nur die noch mögliche Leistung gefordert. Die Meldung verschwindet, sobald
die Begrenzung wieder erfüllbar ist. BMS, SoC-Schutz, Wechselrichterleistung,
Blindleistungsbedarf, Latenz und Rampen können das tatsächliche Einhalten des
Netzlimits verhindern.

Bei Inselbetrieb bleibt der Controller passiv. Bei unbekanntem Netzstatus oder
fehlendem SoC/ESS-/Netzmesswert meldet er `InputUnavailable` und setzt für diesen
Zyklus keine Vorgaben. Es gibt keine erfundenen Nullmesswerte oder gespeicherte
Altgrenze. Damit ist bei Messausfall kein Netzschutz zugesichert. Bereits als
Zahl weitergelieferte, aber veraltete Messwerte erkennt dieser kleine Patch nicht.
Geräte-Kommunikationszustände und diese Kanäle sind in die Betriebsüberwachung
aufzunehmen; die anlagenspezifische Reaktion auf Messausfall bleibt abzustimmen.

## Konfigurationsvorlage

Die folgenden Tabellen geben OSGi-Factory-IDs und ihre tatsächlichen
Konfigurationsschlüssel an. IDs mit `SITE` und Werte in `<...>` sind Platzhalter,
keine direkt importierbare Konfiguration. Vorhandene Komponenten und den
vorhandenen Scheduler bearbeiten, keine konkurrierende zweite Regelkette anlegen.

| Factory-ID | Eigenschaften |
|---|---|
| `Controller.Symmetric.PeakShaving` | `id=ctrlPeakLimitSITE`, `enabled=true`, `ess.id=essSITE`, `meter.id=meterGridSITE`, `limitOnly=true`, `peakShavingPower=<abgestimmtes Momentanlimit in W>`, `rechargePower=0`, `socInfimum=<untere Betriebsgrenze in %>`, `socSupremum=<obere Betriebsgrenze in %>` |
| `Controller.Api.Rest.ReadWrite` (Batterie) | `id=ctrlApiBatterySITE`, `enabled=true`, `port=8084`, `apiTimeout=60` |
| `Controller.Api.Rest.ReadWrite` (PV) | `id=ctrlApiPvSITE`, `enabled=true`, `port=8085`, `apiTimeout=60` |
| `Controller.Symmetric.Balancing` (Beispiel-Ersatzregler) | `id=ctrlFallbackSITE`, `enabled=true`, `ess.id=essSITE`, `meter.id=meterGridSITE`, `targetGridSetpoint=0` |

Ports und Timeouts sind Beispiele und auf freie Ports sowie Service-Schreibintervall
und tolerierbare Ausfallzeit abzustimmen. Balancing als Ersatzregler optimiert
Eigenverbrauch bis zu den zulässigen Grenzen; er reserviert keine Energie für
spätere Lastspitzen. Seine Eignung ist für die Anlage noch zu bestätigen.

Für `Scheduler.FixedOrder` die Eigenschaft `controllers.ids` in dieser Reihenfolge
setzen (Schutz-IDs und weitere vorhandene Controller ergänzen):

```json
[
  "ctrlBatteryProtectionSITE",
  "ctrlSocProtectionSITE",
  "ctrlPeakLimitSITE",
  "ctrlApiBatterySITE",
  "ctrlFallbackSITE",
  "ctrlApiPvSITE"
]
```

Die beiden Schutz-IDs stehen für tatsächlich vorhandene, geeignete Schutzcontroller;
sie sind keine mitgelieferten Factory-IDs. Der PV-Pfad muss zusätzlich in die
vorhandene PV-Regelkette eingeordnet werden. Andere aktive ESS-Regler, etwa
Fixleistung, bisheriges aktives Peak-Shaving, ToU oder Balancing, dürfen nicht
vor der Batterie-API einen widersprüchlichen Gleichheitswert fixieren. Je nach
Zuständigkeit entfernen, deaktivieren oder bewusst nachrangig einordnen. Auch
Modbus-/WebSocket-Schreibpfade und direkte Gerätevorgaben prüfen.

Ein gültiger REST-Gleichheitswert wird durch die vorherigen Ungleichungen begrenzt.
Der nachfolgende Ersatzregler kann ihn nicht verdrängen. Nach API-Timeout wird
keine Gleichheit mehr aus diesem Worker gesetzt und der Ersatzregler nutzt den
verbleibenden Leistungsbereich. Die Netzgrenze wird dabei weiterhin in jedem
Zyklus neu berechnet.

## REST-Schreibpfade

Die Batterie ausschließlich über ihren eigenen Endpunkt bedienen:

```text
POST http://<edge-host>:8084/rest/channel/essSITE/SetActivePowerEquals
Content-Type: application/json
Authorization: Basic <anlagenspezifische Zugangsdaten>

{"value": -100000}
```

Das ist ein Beispiel für 100 kW Laden, kein Live-Aufruf. Zum expliziten Freigeben
am selben Endpunkt `{"value": null}` senden. Das entfernt die Vorgabe dieses
Workers; es ist kein dauerhaft gespeicherter Sollwert. GETs lesen beispielsweise
`/rest/channel/meterGridSITE/ActivePower`, `/rest/channel/essSITE/ActivePower` und
`/rest/channel/essSITE/Soc`. Die Geräte- und Controller-Zustände mit überwachen.
PV-Schreibzugriffe ausschließlich über Port 8085 an den tatsächlich unterstützten
PV-Schreibkanal senden; dessen Namen, Einheit und Freigabeverhalten am Gerät klären.

Jede REST-Instanz besitzt ihren eigenen `ApiWorker`. Alle Schreibvorgaben innerhalb
eines Workers teilen einen Timeout: **jeder Schreibzugriff verlängert ihn für alle
gespeicherten Vorgaben**. GET verlängert ihn nicht. Deshalb am Batterie-Endpunkt
keine periodischen PV- oder sonstigen Schreibzugriffe ausführen. Die beiden Ports
sind keine Zugriffsrechte nach Gerät; die Trennung muss der Service einhalten.
`apiTimeout=0` deaktiviert den Ablauf und ist für diesen Rückfall ungeeignet.

REST schreibt `SetActivePowerEquals` direkt, ohne den Peak-Shaving-PID. Die lokale
Untergrenze ist ebenfalls ungefiltert. Der Ersatzregler verwendet seinen vorhandenen
PID-Pfad, dessen Ausgabe an die verbleibenden Constraints angepasst wird. Es werden
keine Filter- oder Power-Core-Implementierungen geändert. Rampenanforderungen des
realen ESS und zulässige Sprünge externer Vorgaben im Integrationstest prüfen.

## Ausfälle und noch offene Inbetriebnahme

| Fall | Abdeckung / Grenze |
|---|---|
| Service stoppt oder Batterie-Schreibverbindung fällt aus | Nach dem Batterie-API-Timeout übernimmt der nachrangige Ersatzregler, soweit Messwerte verfügbar sind. |
| PV-Schreiben läuft weiter, Batterie-Schreiben stoppt | Getrennte REST-Worker verhindern die Verlängerung des Batterie-Timeouts. |
| Service schreibt weiter auf Basis alter Messwerte | Timeout greift nicht; Frischeprüfung im Service und Integrationstest erforderlich. |
| Messdaten fehlen / Netzstatus unbekannt | `InputUnavailable`; keine neue lokale Grenze. Kein vollständiger Ersatz für eine Messausfallstrategie. |
| ESS kann nicht genug leisten | `LimitUnfulfillable`; physikalische Netzspitze bleibt möglich. |
| PV-Limit wurde im Gerät gespeichert | Ablauf des OpenEMS-Workers garantiert keine Freigabe im PV-Gerät. Gerätespezifisch prüfen. |
| Edge-/Geräteneustart oder Kommunikationsabbruch | Gerätesollwert-Persistenz, Watchdog und Wiederanlauf mit realer Hardware separat prüfen. |

Vor Inbetriebnahme werden benötigt:

- Ausgelieferter OpenEMS-Commit, aktive Komponenten und tatsächliche Scheduler-Reihenfolge.
- ESS-Topologie (ein ESS oder Cluster), IDs und Zuordnung des Netzanschlusszählers.
- BMS-/Wechselrichtergrenzen, Schutzregler, SoC-Betriebsfenster, Rampen und Messzyklus.
- Freigegebenes Momentanlimit und getrennte Rolle des Viertelstunden-/Planungsziels.
  Die 450 kW der Tests sind frei gewählte Testdaten, keine Anlagenfreigabe; auch die
  genannten 400 kW Planungsziel sind nicht automatisch das Momentanlimit.
- Auswahl und Freigabe des Ersatzreglers, dessen Reservehaltung und Ausfallstrategie.
- Service-Repository, getrennte Batterie-/PV-Endpunkte, Schreibfrequenz, Messwert-Frischeprüfung.
- PV-Gerät, Schreibkanal, Einheit, Persistenz und explizite Rücknahme eines Limits.
- Netzwerkzugang, Ports, Authentifizierung und vereinbarter Test-/Rückbauplan.

Die im Handoff genannten Python-Dry-Run-/PV-Freigabefehler sind gesondert mit dem
Service-Entwickler zu beheben. Dieser Patch ändert den Python-Adapter nicht.

## Übertragung auf den geprüften Upstream-Merge

Basis dieses Patches ist `bca84cfe4ce7ffa9d075d62b7ba67c0a3ea151ef`.
Der gesamte Upstream- oder PID-Featurebranch wird nicht integriert.
Beim Übertragen auf `197a890ef3a2f18ffa9daedc4c5745463fc22847` bleibt der neue
Ungleichungspfad unabhängig vom Filter. Den dortigen bestehenden Standardpfad mit
`setActivePowerEqualsWithFilter()` und `setReactivePowerEqualsWithoutFilter()`
erhalten; nicht wieder auf die alten Filternamen umstellen. Die Methoden
`setActivePowerGreaterOrEquals()` und `setActivePowerLessOrEquals()` sind dort
weiterhin vorhanden.
Die Power-Integrationstests müssen auf die dortige v1/v2-Power-Struktur angepasst
und erneut ausgeführt werden. Ein freier Leistungsbereich unter 10 W im neuen
Filterpfad ersetzt die lokale Untergrenze nicht. Es gibt keine neuen oder entfernten
OSGi-Bundles und daher keinen durch diesen Patch bedingten bndrun-Resolve.

## Testnachweis und Prüfgrenzen

Die Verhaltenstests in `LimitOnlyTest` verwenden den echten `EssPowerImpl` mit
aktiviertem PID, dessen Constraint-Löser und den `applyPower`-Callback eines
simulierten ESS. Für die Rückfallfälle laufen der echte `ApiWorker` einschließlich
seines Timeout-Tasks und der echte `Controller.Symmetric.Balancing`. Geprüft werden
zulässiges/begrenztes Laden, Mindestentladung, PV-Überschuss, aktuelle Batterieleistung,
SoC-/Hardware-/Schutzprioritäten, Warnzustände, fehlende Messwerte, Netzstatus,
Blindleistungsfreiheit und API vorhanden/abgelaufen/erneut vorhanden.
Der Rückfalltest verlangt einen eigenen Gleichheits-Arbeitspunkt des Ersatzreglers
oberhalb der reinen Netz-Untergrenze und prüft dessen tatsächliche Anwendung.

Es werden keine HTTP-Server, realen REST-Ports oder Geräte gestartet. Netzwerk,
Authentifizierung, Python-Service und reale Geräteantwort bleiben Gegenstand der
Inbetriebnahme. Die Power-/Filter-Produktionsimplementierung ist unverändert.

Gezielte Prüfung vom Repository-Root:

```bash
./gradlew \
  :io.openems.edge.controller.symmetric.peakshaving:test \
  :io.openems.edge.controller.symmetric.peakshaving:checkstyleMain \
  :io.openems.edge.controller.symmetric.peakshaving:checkstyleTest
```

Die bestehende Testkonfiguration musste zunächst um die bereits im Produktionscode
vorhandenen SoC-Methoden ergänzt werden; der alte PID-Test benötigt außerdem einen
gültigen SoC-Messwert. Seine Leistungs-Erwartungswerte blieben unverändert.
Vor Einführung des Begrenzungsmodus schlug der neue 200-kW-/100-kW-Ladetest wie
beabsichtigt fehl: 0 W statt -100000 W am ESS. Mit `limitOnly=true` besteht er.

Die repositoryweite Ausführung `./gradlew test` am 17.09.2026 scheiterte in
`io.openems.edge.common` an `PidFilterTest.testLimits` (Assertion in Zeile 156).
Dieses Bundle und seine Tests wurden nicht geändert. Der Gesamt-Testlauf ist damit
nicht grün und deckt nicht alle weiteren Bundles ab.

Gezielte Abschlussprüfung am 17.09.2026: **54 Tests bestanden** (Peak-Shaving 20,
ESS-Core 32, Balancing 2), einschließlich des verstärkten Rückfalltests. Für
`io.openems.edge.controller.api.common:test` gibt es keine eigenen Testquellen;
der Worker ist über die neuen Integrationstests abgedeckt. `checkstyleMain` und
`checkstyleTest` des Peak-Shaving-Bundles sind erfolgreich.

Auch `./gradlew checkstyleAll` ist erfolgreich (alle Java-Bundles). Bei der
Kompilierung übriger Bundles erscheinen vorhandene Deprecation-/Typwarnungen.
