#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

client=false
[ "${1:-}" = "--client" ] && client=true

pass=0
fail=0
skip=0
ok()  { echo "PASS  $*"; pass=$((pass + 1)); }
bad() { echo "FAIL  $*"; fail=$((fail + 1)); }
meh() { echo "SKIP  $*"; skip=$((skip + 1)); }

toolchain="$(grep -o 'JavaLanguageVersion.of([0-9]*)' build.gradle.kts | head -1 | tr -dc '0-9')"
home="$(ls -d /usr/lib/jvm/*"-$toolchain"-* 2>/dev/null | head -1)"
if [ -n "$home" ]; then
    export JAVA_HOME="$home"
fi
echo "java $toolchain: ${JAVA_HOME:-$(command -v java)}"

if [ -e /dev/dri ]; then
    echo "gpu: present, using the real driver"
else
    icd=/usr/share/vulkan/icd.d/lvp_icd.json
    if [ ! -f "$icd" ]; then
        echo "No GPU and no lavapipe. Install the software driver:"
        echo "  apt-get install -y mesa-vulkan-drivers vulkan-tools vulkan-validationlayers xvfb"
        exit 1
    fi
    export VK_ICD_FILENAMES="$icd"
    echo "gpu: none, rendering on lavapipe ($icd)"
fi

log="$(mktemp)"

run() {
    local name="$1"
    shift
    if ./gradlew "$@" >"$log" 2>&1; then
        ok "$name"
        grep -E "ALL CHECKS PASSED|HEADLESS RENDER PASSED|programs converted|centre pixel" "$log" \
            | tail -4 | sed 's/^/      /'
    else
        bad "$name"
        grep -E "FAILED|error:|Validation Error|What went wrong" -A1 "$log" | head -20 | sed 's/^/      /'
    fi
}

run "headless checks" smokeTest -Pactinium.headless=true
run "shipped shader corpus" shaderCorpus
run "offscreen draw" renderTest --rerun-tasks

if [ "$client" = true ]; then
    if ! command -v xvfb-run >/dev/null; then
        meh "no xvfb-run; cannot boot the client without a display"
    else
        shot="build/vm-shot.png"
        mkdir -p build
        echo "booting the client under Xvfb on Actinium's own renderer, up to 480s"
        export ACTINIUM_LOG="$log"
        export ACTINIUM_SHOT="$shot"
        timeout 480 xvfb-run -a -s "-screen 0 1280x720x24" bash -c '
            ./gradlew runClient -Prenderer >"$ACTINIUM_LOG" 2>&1 &
            game=$!
            for _ in $(seq 1 80); do
                sleep 5
                grep -q "Actinium online" "$ACTINIUM_LOG" && break
                kill -0 $game 2>/dev/null || break
            done
            sleep 25
            command -v import >/dev/null && import -window root "$ACTINIUM_SHOT" 2>/dev/null
            kill $game 2>/dev/null
            wait $game 2>/dev/null
        '

        if grep -q "Actinium online" "$log"; then
            ok "client reached Actinium startup"
            grep -m4 -E "Actinium online|Vulkan device:|Swapchain|VulkanMod not found" "$log" \
                | sed 's/.*\]: /      /'
        else
            bad "client did not reach Actinium startup"
            grep -m5 -E "Exception|Error|Caused by" "$log" | sed 's/^/      /'
        fi

        if grep -qE "Validation Error|VUID-" "$log"; then
            bad "validation errors during the run"
            grep -m3 -oE "VUID-[A-Za-z0-9-]+" "$log" | sort -u | sed 's/^/      /'
        else
            ok "no validation errors"
        fi

        if [ -f "$shot" ]; then
            ok "captured $shot from the virtual display"
            command -v identify >/dev/null && identify "$shot" | sed 's/^/      /'
        else
            meh "no screenshot; install imagemagick for one"
        fi
    fi
fi

rm -f "$log"
echo "$pass passed, $fail failed, $skip skipped"
[ "$fail" -eq 0 ]
