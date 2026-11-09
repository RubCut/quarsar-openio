CC ?= cc
BUILD_DIR ?= build
CFLAGS ?= -O2 -g
CPPFLAGS += -Iinclude
QIO_WARNINGS := -Wall -Wextra -Wpedantic -Wshadow -Wformat=2 -Wconversion -Wsign-conversion -Werror
QIO_HARDENING := -fstack-protector-strong -D_FORTIFY_SOURCE=2
LDFLAGS ?=
LDLIBS ?=
SOURCES := $(wildcard src/*.c)
OBJECTS := $(patsubst src/%.c,$(BUILD_DIR)/%.o,$(SOURCES))
TARGET := $(BUILD_DIR)/quasar_openio
PACK_SOURCES := $(wildcard pack/*.c)
PACK_OBJECTS := $(patsubst pack/%.c,$(BUILD_DIR)/pack_%.o,$(PACK_SOURCES))
PACK_TARGET := $(BUILD_DIR)/quasar_pack

.PHONY: all static test sanitize fb-info clean
all: $(TARGET) $(PACK_TARGET)

fb-info: $(BUILD_DIR)/fb_info

$(BUILD_DIR)/fb_info: tools/fb_info.c
	@mkdir -p $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(QIO_WARNINGS) -std=c11 -o $@ $<

$(TARGET): $(OBJECTS)
	$(CC) $(CFLAGS) $(QIO_WARNINGS) $(QIO_HARDENING) $(LDFLAGS) -o $@ $^ $(LDLIBS)

$(BUILD_DIR)/%.o: src/%.c include/quasar_openio.h
	@mkdir -p $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(QIO_WARNINGS) $(QIO_HARDENING) -std=c11 -MMD -MP -c $< -o $@

$(PACK_TARGET): $(PACK_OBJECTS) $(BUILD_DIR)/util.o $(BUILD_DIR)/framebuffer.o $(BUILD_DIR)/json.o
	$(CC) $(CFLAGS) $(QIO_WARNINGS) $(QIO_HARDENING) $(LDFLAGS) -o $@ $^ $(LDLIBS)

$(BUILD_DIR)/pack_%.o: pack/%.c pack/quasar_pack.h include/quasar_openio.h
	@mkdir -p $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(QIO_WARNINGS) $(QIO_HARDENING) -std=c11 -MMD -MP -c $< -o $@

static:
	@mkdir -p build/static
	$(MAKE) BUILD_DIR=build/static LDFLAGS="$(LDFLAGS) -static" LDLIBS="$(LDLIBS)"

test: all
	python3 tests/run.py
	python3 tests/test_scripts.py
	python3 tests/test_pack.py
	python3 tests/test_plugin.py

sanitize:
	python3 tests/run.py --sanitize
	python3 tests/test_pack.py --sanitize
	python3 tests/test_plugin.py --sanitize

clean:
	rm -rf build

-include $(OBJECTS:.o=.d) $(PACK_OBJECTS:.o=.d)
