#!/bin/bash
# verificar.sh - Revisa que el servidor quedó bien configurado.
# Uso: sudo ./verificar.sh
# Aquí NO usamos "set -e": queremos que siga aunque una verificación falle.

SERVICIO="servicio"
USUARIO="servicio"
DIR_APP="/opt/servicio"
DIR_LOG="/var/log/servicio"
PASARON=0
TOTAL=0

# Hace falta ser root para poder revisar /var/log/servicio (modo 750).
# Sin esto, algunas verificaciones darían OK por error.
if [ "$(id -u)" -ne 0 ]; then
    echo "Ejecuta con sudo: sudo ./verificar.sh"
    exit 1
fi

# chequear "descripción" 'comando'
# Ejecuta el comando; si termina bien (código 0) muestra OK, si no, FALLA.
chequear() {
    TOTAL=$((TOTAL + 1))
    if eval "$2" &>/dev/null; then
        echo "[OK]    $1"
        PASARON=$((PASARON + 1))
    else
        echo "[FALLA] $1"
    fi
}

# Datos que usan varias verificaciones
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/[^"]*"([0-9]+).*/\1/')
PID=$(systemctl show -p MainPID --value "$SERVICIO")
# Swap total leído de /proc/meminfo (viene en kB, se pasa a MB)
SWAP_MB=$(awk '/^SwapTotal:/ {print int($2/1024)}' /proc/meminfo)

echo "=== Verificación del servidor ==="

# ---- Software ----
chequear "Java 17 o superior instalado (versión ${JAVA_MAJOR:-ninguna})" '[ "${JAVA_MAJOR:-0}" -ge 17 ]'
chequear "El compilador javac está disponible" 'command -v javac'

# ---- Usuario propio ----
chequear "Existe el usuario '$USUARIO'" 'id "$USUARIO"'
chequear "El usuario '$USUARIO' no puede iniciar sesión (nologin)" '[ "$(getent passwd "$USUARIO" | cut -d: -f7)" = "/sbin/nologin" ]'

# ---- Servicio en systemd ----
chequear "El servicio está activo (corriendo ahora)" 'systemctl is-active --quiet "$SERVICIO"'
chequear "El servicio arranca automáticamente al encender la instancia" 'systemctl is-enabled --quiet "$SERVICIO"'
chequear "El servicio se reinicia solo si falla (Restart=on-failure)" '[ "$(systemctl show -p Restart --value "$SERVICIO")" = "on-failure" ]'
chequear "El servicio corre con el usuario '$USUARIO', no con root" '[ "$(ps -o uid= -p "$PID" | tr -d " ")" = "$(id -u "$USUARIO")" ]'

# ---- Permisos ----
chequear "$DIR_APP pertenece al usuario '$USUARIO'" '[ "$(stat -c %U "$DIR_APP")" = "$USUARIO" ]'
chequear "$DIR_LOG pertenece a '$USUARIO' con permisos 750" '[ "$(stat -c "%U %a" "$DIR_LOG")" = "$USUARIO 750" ]'
# find -perm /022 busca archivos con permiso de escritura para el grupo u otros.
# Si no encuentra ninguno, solo el dueño puede escribir.
chequear "Nadie más que '$USUARIO' puede escribir en $DIR_APP ni en $DIR_LOG" '[ -z "$(find "$DIR_APP" "$DIR_LOG" -perm /022)" ]'

# ---- Swap ----
chequear "El swap está activo: ${SWAP_MB} MB" '[ "$SWAP_MB" -gt 0 ]'
chequear "El swap queda activo tras reiniciar (está en /etc/fstab)" 'grep -qE "^/swapfile[[:space:]]" /etc/fstab'

# ---- Servicio respondiendo ----
chequear "El puerto 8080 está escuchando" 'ss -ltn | grep -q ":8080 "'
chequear "/estado respondió en menos de 1 segundo" 'curl -s -f -m 1 -o /dev/null http://localhost:8080/estado'
chequear "/trabajo responde con un resultado" 'curl -s -f -m 10 "http://localhost:8080/trabajo?n=100000" | grep -q primos'
chequear "El registro tiene líneas en $DIR_LOG/servicio.log" '[ -s "$DIR_LOG/servicio.log" ]'

# ---- cgroups ----
chequear "El sistema usa cgroups v2" '[ "$(stat -fc %T /sys/fs/cgroup)" = "cgroup2fs" ]'
chequear "El servicio tiene su propio cgroup" '[ -d /sys/fs/cgroup/system.slice/$SERVICIO.service ]'

echo "================================="
echo "Resultado: $PASARON de $TOTAL verificaciones pasaron."
# Código de salida: 0 si todo pasó, 1 si algo falló
[ "$PASARON" -eq "$TOTAL" ]
