#!/usr/bin/env bash
# v1 bootJar 안의 Hibernate bytecode enhancement가 일관된지 검사한다.
# enhance된 클래스가 부르는 `$$_hibernate_*` 메서드가 대상 클래스에 실제로 선언돼 있어야 한다.
# 엔티티와 그 상위 클래스(@MappedSuperclass)가 서로 다른 상태로 enhance되면 런타임에 NoSuchMethodError가 난다(docs/parity.md "v1 jar 빌드").
# 사용법: check-v1-jar.sh <jar>
set -euo pipefail
jar="$1"
work="$(mktemp -d "${TMPDIR:-/tmp}/snutt-parity-check.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# bootJar 자체 클래스(BOOT-INF/classes)와 같은 빌드의 모듈 jar(BOOT-INF/lib에서 의존성이 아닌 것)를 함께 본다.
# 모듈 jar는 버전 접미사가 없다(core.jar 등).
mkdir -p "$work/jar" "$work/classes"
unzip -q "$jar" -d "$work/jar"
if [ -d "$work/jar/BOOT-INF/classes" ]; then cp -r "$work/jar/BOOT-INF/classes/." "$work/classes/"; fi
for lib in "$work"/jar/BOOT-INF/lib/*.jar; do
  case "$(basename "$lib")" in
    *-[0-9]*.jar) ;;
    *) unzip -q -o "$lib" -d "$work/classes" -x 'META-INF/*' ;;
  esac
done

mapfile -t classes < <(cd "$work/classes" && find . -name '*.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
if [ "${#classes[@]}" -eq 0 ]; then
  echo "check-v1-jar: $jar 에서 클래스를 찾지 못했다" >&2
  exit 1
fi
javap -p -c -cp "$work/classes" "${classes[@]}" > "$work/javap.txt"

# 선언: "class Foo ..." 블록 안의 "$$_hibernate_x(" 메서드 → "Foo.$$_hibernate_x"
awk '
  /^(public |protected |private |abstract |final |static )*(class|interface) / {
    for (i = 1; i <= NF; i++) if ($i == "class" || $i == "interface") { cls = $(i + 1); break }
    gsub(/\./, "/", cls); sub(/<.*/, "", cls)
  }
  /^  [^ ].*\$\$_hibernate_[A-Za-z0-9_]*\(/ {
    m = $0; sub(/\(.*/, "", m); n = split(m, p, " "); print cls "." p[n]
  }
' "$work/javap.txt" | sort -u > "$work/declared.txt"

# 참조: "// Method Foo.$$_hibernate_x:(...)" 중 이 jar가 만든 클래스를 가리키는 것
{ grep -o '// Method [^ ]*\.\$\$_hibernate_[A-Za-z0-9_]*' "$work/javap.txt" || true; } \
  | sed 's|^// Method ||' | sort -u > "$work/referenced.txt"
own="$(printf '%s\n' "${classes[@]}" | tr . / )"
missing="$(comm -23 "$work/referenced.txt" "$work/declared.txt" | while IFS= read -r ref; do
  if grep -qxF "${ref%.*}" <<< "$own"; then echo "$ref"; fi
done)"

if [ -n "$missing" ]; then
  {
    echo "check-v1-jar: $jar 의 Hibernate enhancement가 일관되지 않다. 다음 메서드를 부르지만 선언한 클래스가 없다:"
    sed 's/^/  /' <<< "$missing"
    echo "엔티티와 상위 클래스가 서로 다른 순서로 enhance된 jar다. 이 jar로 v1을 띄우면 NoSuchMethodError가 난다."
  } >&2
  exit 1
fi
echo "check-v1-jar: $(basename "$jar") ok (enhanced 메서드 참조 $(wc -l < "$work/referenced.txt")개)"
