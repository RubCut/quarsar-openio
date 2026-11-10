/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_openio.h"
#include <limits.h>
#include <string.h>

static void skip_space(const uint8_t *line, size_t length, size_t *pos)
{
    while (*pos < length && (line[*pos] == ' ' || line[*pos] == '\t' ||
                             line[*pos] == '\r' || line[*pos] == '\n'))
        ++*pos;
}

static int hex4(const uint8_t *s, unsigned *value)
{
    unsigned result = 0;
    for (size_t i = 0; i < 4; ++i) {
        unsigned digit;
        if (s[i] >= '0' && s[i] <= '9') digit = (unsigned)s[i] - '0';
        else if (s[i] >= 'a' && s[i] <= 'f') digit = (unsigned)s[i] - 'a' + 10;
        else if (s[i] >= 'A' && s[i] <= 'F') digit = (unsigned)s[i] - 'A' + 10;
        else return -1;
        result = result * 16 + digit;
    }
    *value = result;
    return 0;
}

/* The API's field names and values are ASCII. Reject unsupported Unicode
 * rather than silently truncating it. JSON escapes are decoded, not executed. */
static int parse_string(const uint8_t *line, size_t length, size_t *pos,
                        char *output, size_t capacity, size_t *output_length,
                        const uint8_t **raw, size_t *raw_length)
{
    if (*pos >= length || line[*pos] != '"')
        return -1;
    ++*pos;
    size_t start = *pos;
    size_t used = 0;
    bool escaped = false;
    while (*pos < length) {
        unsigned char c = line[*pos];
        if (c == '"') {
            if (used >= capacity)
                return -1;
            output[used] = '\0';
            *output_length = used;
            if (!escaped && raw != NULL) {
                *raw = line + start;
                *raw_length = *pos - start;
            }
            ++*pos;
            return 0;
        }
        if (c < 0x20 || c >= 0x80)
            return -1;
        ++*pos;
        if (c == '\\') {
            escaped = true;
            if (*pos >= length)
                return -1;
            c = line[(*pos)++];
            switch (c) {
            case '"': case '\\': case '/': break;
            case 'b': c = '\b'; break;
            case 'f': c = '\f'; break;
            case 'n': c = '\n'; break;
            case 'r': c = '\r'; break;
            case 't': c = '\t'; break;
            case 'u': {
                unsigned code;
                if (length - *pos < 4 || hex4(line + *pos, &code) != 0)
                    return -1;
                *pos += 4;
                /* The parsed API fields are stored as C strings. Accepting an
                 * escaped NUL would let "status\u0000suffix" compare equal to
                 * "status" in command dispatch. */
                if (code == 0 || code >= 0x80)
                    return -1;
                if (code >= 0xd800 && code <= 0xdfff)
                    return -1;
                c = (unsigned char)code;
                break;
            }
            default: return -1;
            }
        }
        if (used >= capacity)
            return -1;
        output[used++] = (char)c;
    }
    return -1;
}

int qio_parse_request(const uint8_t *line, size_t length, qio_request *request)
{
    memset(request, 0, sizeof(*request));
    size_t pos = 0;
    unsigned seen = 0;
    skip_space(line, length, &pos);
    if (pos >= length || line[pos++] != '{')
        return -1;
    skip_space(line, length, &pos);
    if (pos < length && line[pos] == '}')
        return -1; /* action is mandatory */
    for (;;) {
        char key[32];
        char value[2048];
        size_t key_length, value_length;
        const uint8_t *raw = NULL;
        size_t raw_length = 0;
        skip_space(line, length, &pos);
        if (parse_string(line, length, &pos, key, sizeof(key), &key_length,
                         NULL, NULL) != 0)
            return -1;
        skip_space(line, length, &pos);
        if (pos >= length || line[pos++] != ':')
            return -1;
        skip_space(line, length, &pos);
        if (parse_string(line, length, &pos, value, sizeof(value), &value_length,
                         &raw, &raw_length) != 0)
            return -1;
        if (strcmp(key, "action") == 0) {
            if ((seen & 1U) != 0 || value_length >= sizeof(request->action))
                return -1;
            seen |= 1U;
            memcpy(request->action, value, value_length + 1);
        } else if (strcmp(key, "data") == 0) {
            if ((seen & 2U) != 0)
                return -1;
            seen |= 2U;
            request->has_data = true;
            request->data_length = value_length;
            /* Always NUL-terminate: text commands use strcmp, draw uses length. */
            memcpy(request->data_storage, value, value_length + 1);
            request->data = request->data_storage;
            (void)raw; (void)raw_length;
        } else {
            return -1; /* no command execution, filenames or arbitrary properties */
        }
        skip_space(line, length, &pos);
        if (pos >= length)
            return -1;
        if (line[pos] == '}') {
            ++pos;
            break;
        }
        if (line[pos++] != ',')
            return -1;
    }
    skip_space(line, length, &pos);
    if (pos != length || (seen & 1U) == 0)
        return -1;
    return 0;
}

static int sextet(unsigned char c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

int qio_base64_decode(const char *input, size_t length, uint8_t *output,
                      size_t capacity, size_t *decoded)
{
    *decoded = 0;
    if (length == 0 || length % 4 != 0 || length > SIZE_MAX / 3)
        return -1;
    size_t used = 0;
    for (size_t i = 0; i < length; i += 4) {
        int a = sextet((unsigned char)input[i]);
        int b = sextet((unsigned char)input[i + 1]);
        if (a < 0 || b < 0)
            return -1;
        int c = input[i + 2] == '=' ? 0 : sextet((unsigned char)input[i + 2]);
        int d = input[i + 3] == '=' ? 0 : sextet((unsigned char)input[i + 3]);
        if (c < 0 || d < 0 || (input[i + 2] == '=' && input[i + 3] != '='))
            return -1;
        size_t count = input[i + 2] == '=' ? 1 : (input[i + 3] == '=' ? 2 : 3);
        if (i + 4 != length && count != 3)
            return -1;
        /* Canonical padding: unused low bits must be zero. */
        if (count == 1 && (b & 15) != 0)
            return -1;
        if (count == 2 && (c & 3) != 0)
            return -1;
        if (used + count > capacity)
            return -1;
        uint32_t bits = ((uint32_t)a << 18) | ((uint32_t)b << 12) |
                        ((uint32_t)c << 6) | (uint32_t)d;
        output[used++] = (uint8_t)(bits >> 16);
        if (count > 1) output[used++] = (uint8_t)(bits >> 8);
        if (count > 2) output[used++] = (uint8_t)bits;
    }
    *decoded = used;
    return 0;
}
