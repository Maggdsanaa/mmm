#!/usr/bin/env bash
# يشغّل اختبارات الترحيل والمزامنة محليًا مقابل خادم يحاكي Firebase (يحتاج kotlinc + JDK 17+ و org.json على الـclasspath).
# الاستخدام: KOTLINC_HOME=/path/to/kotlinc ORGJSON=/path/to/org.json.classes tools/relay-test/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
K="${KOTLINC_HOME:?}"; OJ="${ORGJSON:?}"; B=$(mktemp -d); PORT=8799
"$K/bin/kotlinc" core/src/main/kotlin/com/uniatt/core/*.kt -d "$B/core"
"$K/bin/kotlinc" tools/relay-test/RelayTest.kt -cp "$B/core" -d "$B/t1"
"$K/bin/kotlinc" admin/src/main/kotlin/com/uniatt/admin/AdminStore.kt admin/src/main/kotlin/com/uniatt/admin/AdminSync.kt admin/src/main/kotlin/com/uniatt/admin/AdminOps.kt \
  -cp "$B/core:${ANDROID_JAR:?}:$K/lib/kotlinx-coroutines-core-jvm.jar:$OJ" -d "$B/admin"
"$K/bin/kotlinc" tools/relay-test/AdminSyncTest.kt -cp "$B/core:$B/admin:${ANDROID_JAR}:$K/lib/kotlinx-coroutines-core-jvm.jar:$OJ" -d "$B/t2"
"$K/bin/kotlinc" tools/relay-test/AdminOpsTest.kt -cp "$B/core:$B/admin:${ANDROID_JAR}:$K/lib/kotlinx-coroutines-core-jvm.jar:$OJ" -d "$B/t3"
python3 tools/relay-test/fake_rtdb.py $PORT & SRV=$!; python3 tools/relay-test/fake_rtdb.py $((PORT+1)) dead & SRV2=$!; trap "kill $SRV $SRV2" EXIT; sleep 1
CP="$K/lib/kotlin-stdlib.jar:$K/lib/kotlinx-coroutines-core-jvm.jar:$OJ:$B/core:$B/admin"
java -cp "$B/t1:$CP" RelayTestKt http://127.0.0.1:$PORT http://127.0.0.1:$((PORT+1))
java -cp "$B/t2:$CP" AdminSyncTestKt http://127.0.0.1:$PORT http://127.0.0.1:$((PORT+1)) http://127.0.0.1:$((PORT+1))
java -cp "$B/t3:$CP" AdminOpsTestKt
