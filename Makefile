MVN ?= mvn
JVM_ARGS ?= -Xmx4g
MODE ?= threadpool
PIPELINE ?= gaussian
export PIXELRUSH_INPUT := $(INPUT)
export PIXELRUSH_OUTPUT := $(OUTPUT)
export PIXELRUSH_RADIUS := $(RADIUS)
export PIXELRUSH_SIGMA := $(SIGMA)
export PIXELRUSH_BENCH_CSV := $(CSV)
export PIXELRUSH_BENCH_THREADS := $(THREADS)
export PIXELRUSH_BENCH_FILTERS := $(FILTERS)
export PIXELRUSH_BENCH_COUNT := $(COUNT)
export PIXELRUSH_BENCH_WIDTH := $(WIDTH)
export PIXELRUSH_BENCH_HEIGHT := $(HEIGHT)
export PIXELRUSH_BENCH_WARMUP := $(WARMUP)
export PIXELRUSH_BENCH_ITERATIONS := $(ITERATIONS)

.PHONY: build test run-seq run-par benchmark

build:
	$(MVN) -B -ntp package

test:
	$(MVN) -B -ntp test

run-seq:
	@test -n "$$PIXELRUSH_INPUT" && test -n "$$PIXELRUSH_OUTPUT" || { \
		echo 'Укажите входной и выходной каталоги через INPUT и OUTPUT.' >&2; exit 2; \
	}
	PIXELRUSH_MODE=sequential PIXELRUSH_THREADS=1 PIXELRUSH_PIPELINE= \
	$(MVN) -B -ntp spring-boot:run \
		-Dspring-boot.run.arguments=--batch \
		'-Dspring-boot.run.jvmArguments=$(JVM_ARGS)'

run-par:
	@test -n "$$PIXELRUSH_INPUT" && test -n "$$PIXELRUSH_OUTPUT" || { \
		echo 'Укажите входной и выходной каталоги через INPUT и OUTPUT.' >&2; exit 2; \
	}
	PIXELRUSH_MODE="$(MODE)" PIXELRUSH_THREADS="$(or $(THREADS),4)" PIXELRUSH_PIPELINE="$(PIPELINE)" \
	$(MVN) -B -ntp spring-boot:run \
		-Dspring-boot.run.arguments=--batch \
		'-Dspring-boot.run.jvmArguments=$(JVM_ARGS)'

benchmark:
	$(MVN) -B -ntp spring-boot:run \
		-Dspring-boot.run.main-class=com.team.imgproc.benchmark.BenchmarkRunner \
		'-Dspring-boot.run.jvmArguments=$(JVM_ARGS)'
