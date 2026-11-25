#!/bin/bash
# prueba_garantias.sh - Consulta /estado una vez por segundo durante 120 s,
# guarda los tiempos en un CSV y muestra un resumen.
# Uso: ./prueba_garantias.sh [archivo.csv]
SALIDA="${1:-/tmp/tiempos.csv}"
# PID del servicio al empezar: si cambia al terminar, el servicio se reinicio o lo mataron
PID_INI=$(systemctl show -p MainPID --value servicio)

echo "segundo,tiempo_s" > "$SALIDA"
for i in $(seq 1 120); do
    # %{time_total}: tiempo total de la peticion. -m 10: espera maxima 10 s.
    # Si curl falla (servicio caido o timeout) se registra 10 s.
    t=$(curl -s -f -o /dev/null -w "%{time_total}" -m 10 http://localhost:8080/estado) || t=10
    echo "$i,$t" >> "$SALIDA"
    sleep 1
done

PID_FIN=$(systemctl show -p MainPID --value servicio)

# Resumen: ordena los tiempos y calcula % bajo 1 s, promedio, p95 y maximo
tail -n +2 "$SALIDA" | cut -d, -f2 | LC_ALL=C sort -n | LC_ALL=C awk '
    { v[NR] = $1; suma += $1; if ($1 < 1) rapidas++ }
    END {
        p95 = v[int(NR * 0.95)]
        printf "Respuestas: %d | menores a 1 s: %d (%.1f %%) | promedio: %.3f s | p95: %.3f s | maximo: %.3f s\n",
               NR, rapidas, 100 * rapidas / NR, suma / NR, p95, v[NR]
        if (100 * rapidas / NR >= 95) print "Condicion 1 (>= 95 % bajo 1 s): SE CUMPLE"
        else print "Condicion 1 (>= 95 % bajo 1 s): NO SE CUMPLE"
    }'

if systemctl is-active --quiet servicio && [ "$PID_INI" = "$PID_FIN" ]; then
    echo "Condicion 2 (servicio vivo y sin reinicios): SE CUMPLE"
else
    echo "Condicion 2 (servicio vivo y sin reinicios): NO SE CUMPLE"
fi
