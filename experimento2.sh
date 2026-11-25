#!/bin/bash
# experimento2.sh - Experimento 2 (memoria): el descontrolado intenta reservar 1200 MB
# (la RAM es de 913 MB). Se prueba con/sin limites y con/sin swap. Al final, la prueba
# de garantias completa (CPU + memoria) con todas las garantias activas.
# Uso: ./experimento2.sh [repeticiones=1]
REPS="${1:-1}"
DIR="$HOME/exp2"; mkdir -p "$DIR"
cd "$(dirname "$(readlink -f "$0")")"
[ -f /opt/herramientas/Descontrolado.class ] || { echo "Falta /opt/herramientas: ejecuta sudo ./instalar.sh"; exit 1; }
UEV=/sys/fs/cgroup/user.slice/memory.events
SEV=/sys/fs/cgroup/system.slice/servicio.service/memory.events

# Cuantos procesos ha matado el OOM killer en un cgroup (campo oom_kill de memory.events)
oomk() { awk '/^oom_kill /{print $2}' "$1" 2>/dev/null; }

# Aplica o quita los limites de memoria (solo hasta el proximo reinicio: --runtime)
limites() {
    if [ "$1" = sin ]; then
        sudo systemctl set-property --runtime user.slice MemoryHigh=infinity MemoryMax=infinity
        sudo systemctl set-property --runtime system.slice MemoryLow=0
        sudo systemctl set-property --runtime servicio.service MemoryLow=0 MemoryHigh=infinity MemoryMax=infinity
    else
        sudo systemctl set-property --runtime user.slice MemoryHigh=450M MemoryMax=550M
        sudo systemctl set-property --runtime system.slice MemoryLow=150M
        sudo systemctl set-property --runtime servicio.service MemoryLow=150M MemoryHigh=250M MemoryMax=300M
    fi
}

# Activa o desactiva el swap
swap() {
    if [ "$1" = con ]; then
        swapon --show=NAME --noheadings | grep -q '^/swapfile$' || sudo swapon /swapfile
    else
        if swapon --show=NAME --noheadings | grep -q '^/swapfile$'; then sudo swapoff /swapfile; fi
    fi
}

# Pase lo que pase, al terminar se deja el sistema como lo configura instalar.sh
trap 'limites con; swap con' EXIT

[ -s "$DIR/resumen.csv" ] || echo "corrida,rep,limites,swap,descontrolado,mb_reservados,oom_user_slice,oom_servicio,proceso_matado,swap_max_mb,fallos_mayores_delta,fallos_mayores_max_por_s,servicio_mem_max_mb,estado_bajo_1s_pct,estado_prom_s,estado_max_s,servicio_vivo" > "$DIR/resumen.csv"

# corrida nombre limites(sin|con) swap(sin|con) rep hilos_cpu
corrida() {
    local N="$1" L="$2" S="$3" R="$4" H="${5:-0}" P="$DIR/${1}_$4"
    echo "=== $N (rep $R): limites=$L, swap=$S, hilos de CPU=$H ($(date +%H:%M:%S)) ==="
    swap "$S"; limites "$L"; sleep 3
    rm -f "$P".*
    local INI U0 S0 U1 S1 MON DES CODIGO
    INI=$(date "+%Y-%m-%d %H:%M:%S")
    U0=$(oomk $UEV); S0=$(oomk $SEV)
    java -cp /opt/herramientas Monitor 2 "$P.monitor.csv" 5 >/dev/null 2>&1 &
    MON=$!
    # -Xmx1000m: sin este limite Java no dejaria reservar 1200 MB
    java -Xmx1000m -cp /opt/herramientas Descontrolado 110 ${MAXMB:-850} "$H" > "$P.descontrolado.txt" 2>&1 &
    DES=$!
    sleep 2
    ./prueba_garantias.sh "$P.estado.csv" | tee "$P.prueba.txt"
    wait $DES; CODIGO=$?
    sleep 3; kill $MON 2>/dev/null; wait $MON 2>/dev/null
    U1=$(oomk $UEV); S1=$(oomk $SEV)
    sudo journalctl -k --since "$INI" --no-pager 2>/dev/null | grep -iE "out of memory|killed process" > "$P.kernel.txt"

    local QUIEN RES MB M E VIVO
    QUIEN=$(grep -oE 'Killed process [0-9]+ \([^)]*\)' "$P.kernel.txt" | head -1 | sed 's/.*(\(.*\)).*/\1/')
    [ -z "$QUIEN" ] && QUIEN=ninguno
    if grep -q "Tiempo cumplido" "$P.descontrolado.txt"; then RES=termino_solo; else RES="murio_codigo_$CODIGO"; fi
    MB=$(grep -o 'reservada: [0-9]*' "$P.descontrolado.txt" | tail -1 | grep -o '[0-9]*')
    M=$(tail -n +2 "$P.monitor.csv" | LC_ALL=C awk -F, 'NR==1{f0=$8} {if($5>s)s=$5; if($9>fm)fm=$9; if($10!=""&&$10>sm)sm=$10; f1=$8} END{printf "%.0f,%d,%.1f,%.0f",s,f1-f0,fm,sm}')
    E=$(tail -n +2 "$P.estado.csv" | LC_ALL=C awk -F, '{n++; if($2<1)b++; s+=$2; if($2>m)m=$2} END{printf "%.1f,%.3f,%.3f",100*b/n,s/n,m}')
    if grep -q "Condicion 2.*: SE CUMPLE" "$P.prueba.txt"; then VIVO=si; else VIVO=no; fi
    echo "$N,$R,$L,$S,$RES,${MB:-0},$((${U1:-0}-${U0:-0})),$((${S1:-0}-${S0:-0})),$QUIEN,$M,$E,$VIVO" >> "$DIR/resumen.csv"
    sleep 20
}

for r in $(seq 1 "$REPS"); do
    for c in ${CORRIDAS:-2 3 1}; do
        case $c in
            1) corrida sin_limites_con_swap sin con "$r" ;;
            2) corrida con_limites_con_swap con con "$r" ;;
            3) corrida con_limites_sin_swap con sin "$r" ;;
        esac
    done
done
if [ "${FINAL:-si}" = si ]; then corrida prueba_final_cpu_mem con con 1 4; fi

echo; echo "==== RESUMEN ===="; column -s, -t "$DIR/resumen.csv"
