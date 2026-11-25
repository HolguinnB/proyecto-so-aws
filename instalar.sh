#!/bin/bash
# instalar.sh - Deja el servidor listo en una instancia nueva.
# Se puede ejecutar varias veces: cada paso revisa si ya está hecho.
# Uso: sudo ./instalar.sh

# -e: parar si un comando falla | -u: error si se usa una variable sin definir
# -o pipefail: un fallo dentro de una tubería (|) también cuenta como fallo
set -euo pipefail

# ---- Configuración ----
SERVICIO="servicio"
USUARIO="servicio"
DIR_APP="/opt/${SERVICIO}"
DIR_LOG="/var/log/${SERVICIO}"
SWAP_MB=1024
# Carpeta donde está este script (para encontrar src/ sin importar desde dónde se ejecute)
RAIZ="$(dirname "$(readlink -f "$0")")"

# El script modifica el sistema, así que necesita ser administrador (root)
if [ "$(id -u)" -ne 0 ]; then
    echo "Ejecuta con sudo: sudo ./instalar.sh"
    exit 1
fi

echo "[1/7] Instalando Java 17..."
# Si ya está instalado, dnf no hace nada
dnf install -y java-17-amazon-corretto-devel

echo "[2/7] Creando usuario y grupo del servicio..."
# '||' significa: si lo de la izquierda falla (no existe), ejecuta lo de la derecha
getent group "$USUARIO" >/dev/null || groupadd --system "$USUARIO"
# --system: cuenta de servicio | --no-create-home: sin carpeta personal
# --shell /sbin/nologin: nadie puede iniciar sesión con este usuario
id "$USUARIO" &>/dev/null || useradd --system --gid "$USUARIO" --no-create-home --shell /sbin/nologin "$USUARIO"

echo "[3/7] Creando directorios con permisos mínimos..."
# 750 = el dueño (servicio) lee/escribe/entra; el grupo lee/entra; los demás nada
install -d -o "$USUARIO" -g "$USUARIO" -m 750 "$DIR_APP" "$DIR_LOG"

echo "[4/7] Compilando y copiando el servicio..."
# -d indica en qué carpeta se dejan los .class compilados
javac -d "$DIR_APP" "$RAIZ/src/servicio/Servicio.java" "$RAIZ/src/servicio/Registro.java"
chown -R "$USUARIO:$USUARIO" "$DIR_APP"
# Dueño: leer/escribir | grupo: solo leer | otros: nada. (X = 'entrar' solo en carpetas)
chmod -R u=rwX,g=rX,o= "$DIR_APP"

echo "[5/7] Configurando swap de ${SWAP_MB} MB..."
# Solo crea y activa el swap si todavía no está activo
if ! swapon --show=NAME --noheadings | grep -q '^/swapfile$'; then
    # Reserva un archivo de ceros del tamaño del swap
    [ -f /swapfile ] || dd if=/dev/zero of=/swapfile bs=1M count="$SWAP_MB" status=none
    chmod 600 /swapfile        # solo root puede leerlo (contiene memoria del sistema)
    mkswap /swapfile >/dev/null  # lo formatea como área de swap
    swapon /swapfile             # lo activa ahora mismo
fi
# Agrega la línea a /etc/fstab para que se active en cada reinicio (sin duplicarla)
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab

echo "[6/7] Registrando el servicio en systemd..."
# Archivo de configuración del servicio. Sobrescribirlo siempre es seguro.
cat > "/etc/systemd/system/${SERVICIO}.service" <<UNIDAD
[Unit]
Description=Servicio de la empresa (HTTP en el puerto 8080)
After=network.target

[Service]
User=${USUARIO}
Group=${USUARIO}
ExecStart=/usr/bin/java -Xmx128m -XX:+UseSerialGC -Dlog.dir=${DIR_LOG} -cp ${DIR_APP} Servicio
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
UNIDAD

echo "[extra] Compilando monitor y descontrolado..."
# Herramientas de medición: legibles por todos para que cualquier usuario pueda ejecutarlas
install -d -m 755 /opt/herramientas
javac -encoding UTF-8 -d /opt/herramientas "$RAIZ/src/monitor/Monitor.java" "$RAIZ/src/descontrolado/Descontrolado.java"
chmod -R a+rX /opt/herramientas

echo "[extra] Configurando garantías de recursos (cgroups)..."
# Drop-in = archivo que agrega propiedades a una unidad de systemd sin tocar la original.
# Sobrescribirlos siempre es seguro (idempotente).
install -d /etc/systemd/system/servicio.service.d /etc/systemd/system/user.slice.d /etc/systemd/system/system.slice.d

# SERVICIO: memoria protegida (low), con estrangulamiento (high) y tope duro (max)
cat > /etc/systemd/system/servicio.service.d/50-garantias.conf <<GARANTIA
[Service]
MemoryLow=150M
MemoryHigh=250M
MemoryMax=300M
GARANTIA

# system.slice (padre del servicio): memory.low solo protege de verdad si el padre también lo declara
cat > /etc/systemd/system/system.slice.d/50-garantias.conf <<GARANTIA
[Slice]
MemoryLow=150M
GARANTIA

# USUARIOS (user.slice): poco peso de CPU, tope de 1 CPU y límites de memoria
cat > /etc/systemd/system/user.slice.d/50-garantias.conf <<GARANTIA
[Slice]
CPUWeight=20
CPUQuota=100%
MemoryHigh=450M
MemoryMax=550M
GARANTIA

echo "[7/7] Arrancando el servicio..."
systemctl daemon-reload                 # releer los archivos de configuración
systemctl enable "$SERVICIO"            # que arranque solo al encender la instancia
systemctl restart "$SERVICIO"           # (re)iniciarlo ahora con la versión nueva
sleep 3

if systemctl is-active --quiet "$SERVICIO"; then
    echo "Listo: el servicio está corriendo."
else
    echo "El servicio NO arrancó. Últimas líneas del registro:"
    journalctl -u "$SERVICIO" -n 20 --no-pager
    exit 1
fi
