# Tim's Plan

## Ideen & Notizen

### Aktivierungscode-System

- Codes werden vorab auf dem Server generiert (zufällig, kein Name drin, immer gleich groß)
- Code ist beim Generieren noch "leer" — nur `used: true/false` + Erstelldatum
- Wenn jemand die App zum ersten Mal öffnet:
  1. Aktivierungscode eingeben
  2. Im nächsten Schritt: eigenen Namen eingeben
  3. Name + Code werden dann zusammen auf dem Server gespeichert
- Gerät wird beim Einlösen automatisch registriert — kein separater Schritt nötig

### Backend-Endpunkt (später)

`POST /activation/redeem`
- Felder: `code`, `device_id`, `display_name`
- Prüft ob Code existiert und noch unused ist
- Speichert Name + Gerät, markiert Code als used
- Gibt 403 zurück wenn Code ungültig oder bereits verwendet
