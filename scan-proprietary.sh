#!/bin/bash
# Verifica que nenhum conteudo especifico de um cliente entrou nesta arvore.
#
# Os padroes a procurar vivem num ficheiro externo (nao versionado), para que
# este script possa ser publicado sem revelar o vocabulario que procura.
# Cria o teu a partir de patterns.example:
#
#     cp patterns.example patterns.local
#     $EDITOR patterns.local          # um padrao ERE por linha
#     ./scan-proprietary.sh
#
# Sem patterns.local o script corre apenas as verificacoes estruturais.

cd "$(dirname "$0")" || exit 2

EXCL="--exclude-dir=target --exclude-dir=classes --exclude-dir=generated --exclude-dir=.cp --exclude-dir=.git"
PATTERNS="${SCAN_PATTERNS:-patterns.local}"
FOUND=0

check() {  # etiqueta, comando...
  local label="$1"; shift
  local out
  out=$("$@" 2>/dev/null | grep -vE 'scan-proprietary\.sh|patterns\.(local|example)' | head -6)
  if [ -n "$out" ]; then
    echo "FALHA  $label"
    echo "$out" | sed 's/^/         /'
    FOUND=1
  else
    echo "ok     $label"
  fi
}

echo "=============== VERIFICACOES ESTRUTURAIS ==============="

# Ficheiros que so podem existir se codigo de cliente tiver entrado.
check "COBOL fora de examples/ e testes" \
  find . -name '*.cbl' -not -path './examples/*' -not -path '*/src/test/*' -not -path '*/target/*'
check "DDS / display files" \
  find . \( -name '*.dspf' -o -name '*.pf' -o -name '*.lf' -o -name '*.pcml' \) -not -path '*/target/*'
check "schemas DDS fora de examples/" \
  find . -name '*_schema.json' -not -path './examples/*' -not -path '*/target/*'
check "baselines de equivalencia (contem nomes reais)" \
  find . -path '*equivalence/golden*' -name '*.json'
check "ficheiros .properties (credenciais)" \
  find . -name '*.properties' -not -path '*/src/*' -not -path '*/target/*'
check "logs" \
  find . -name '*.log' -not -path '*/target/*'
check "ficheiros .class soltos" \
  find . -name '*.class' -not -path '*/target/*' -not -path '*/classes/*'
check "JARs fora de target/" \
  find . -name '*.jar' -not -path '*/target/*'
check "repositorios git aninhados" \
  find . -mindepth 2 -name '.git' -maxdepth 3

echo
echo "=============== INFRAESTRUTURA ==============="
check "IPs privados" \
  grep -rIoE $EXCL '\b(10|172|192)\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\b' .
check "URLs jdbc" \
  grep -rIoE $EXCL 'jdbc:[a-z0-9]+://[^" ]+' .
check "hosts as400" \
  grep -rIoE $EXCL 'as400://[^" ]+' .

echo
echo "=============== VOCABULARIO DO CLIENTE ==============="
if [ -f "$PATTERNS" ]; then
  n=0
  while IFS= read -r pat; do
    [ -z "$pat" ] && continue
    case "$pat" in \#*) continue ;; esac
    n=$((n + 1))
    check "padrao #$n" grep -rIlE $EXCL -i "$pat" .
  done < "$PATTERNS"
  [ "$n" -eq 0 ] && echo "aviso  $PATTERNS esta vazio"
else
  echo "aviso  $PATTERNS nao existe — verificacoes de vocabulario ignoradas"
  echo "       cp patterns.example patterns.local e edita para as tuas"
fi

echo
echo "======================================================="
if [ $FOUND -eq 0 ]; then
  echo "LIMPO — nenhum conteudo de cliente encontrado."
else
  echo "ENCONTRADO — ver as linhas FALHA acima."
fi
exit $FOUND
