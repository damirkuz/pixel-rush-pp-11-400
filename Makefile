MVN ?= mvn
JVM_ARGS ?= -Xmx4g
export PIXELRUSH_INPUT := $(INPUT)
export PIXELRUSH_OUTPUT := $(OUTPUT)
export PIXELRUSH_RADIUS := $(RADIUS)
export PIXELRUSH_SIGMA := $(SIGMA)

.PHONY: build test run-seq run-par benchmark

build:
	$(MVN) -B -ntp package

test:
	$(MVN) -B -ntp test

run-seq:
	@test -n "$$PIXELRUSH_INPUT" && test -n "$$PIXELRUSH_OUTPUT" || { \
		echo 'Укажите входной и выходной каталоги через INPUT и OUTPUT.' >&2; exit 2; \
	}
	$(MVN) -B -ntp spring-boot:run \
		-Dspring-boot.run.main-class=com.team.imgproc.demo.SequentialDemo \
		'-Dspring-boot.run.jvmArguments=$(JVM_ARGS)'

run-par:
	@echo 'Цель будет доступна после подключения параллельных движков и CLI.' >&2
	@exit 2

benchmark:
	@echo 'Цель будет доступна после подключения бенчмарк-раннера.' >&2
	@exit 2
