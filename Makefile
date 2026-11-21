MVN ?= mvn
JVM_ARGS ?= -Xmx4g

.PHONY: build test run-seq run-par benchmark

build:
	$(MVN) -B -ntp package

test:
	$(MVN) -B -ntp test

run-seq:
	@echo 'Цель будет доступна после интеграции feat/core-gaussian.' >&2
	@exit 2

run-par:
	@echo 'Цель будет доступна после подключения параллельных движков и CLI.' >&2
	@exit 2

benchmark:
	@echo 'Цель будет доступна после подключения бенчмарк-раннера.' >&2
	@exit 2
