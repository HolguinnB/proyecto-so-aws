#!/bin/bash
# carga_servicio.sh - Simula clientes que piden /trabajo sin parar.
# Uso: ./carga_servicio.sh [clientes=6] [segundos=130] [n=1000000]
CLIENTES="${1:-6}"; SEG="${2:-130}"; N="${3:-1000000}"
FIN=$((SECONDS + SEG))
for c in $(seq 1 "$CLIENTES"); do
    ( while [ $SECONDS -lt $FIN ]; do
          curl -s -o /dev/null -m 30 "http://localhost:8080/trabajo?n=$N"
      done ) &
done
wait
