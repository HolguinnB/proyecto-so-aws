#!/bin/bash
# carga_servicio.sh - Clientes que piden /trabajo sin parar y registran cuanto tarda cada pedido.
# Uso: ./carga_servicio.sh [clientes=6] [segundos=130] [n=1000000] [salida.csv]
CLIENTES="${1:-6}"; SEG="${2:-130}"; N="${3:-1000000}"; SALIDA="${4:-/tmp/trabajo.csv}"
echo "cliente,tiempo_s" > "$SALIDA"
FIN=$((SECONDS + SEG))
for c in $(seq 1 "$CLIENTES"); do
    ( while [ $SECONDS -lt $FIN ]; do
          t=$(curl -s -o /dev/null -w "%{time_total}" -m 30 "http://localhost:8080/trabajo?n=$N") || t=30
          echo "$c,$t" >> "$SALIDA"
      done ) &
done
wait
awk -F, 'NR>1 {s+=$2; n++} END {if (n) printf "/trabajo: %d pedidos completados | promedio %.2f s\n", n, s/n}' "$SALIDA"
