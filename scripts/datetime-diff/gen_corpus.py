#!/usr/bin/env python3
"""Builds corpus.tsv for the timelib port from timelib's C tests and php-src's ext/date tests.

Usage: gen_corpus.py <ref-dir>   (ref-dir holds timelib/ and php-src/ checkouts)
Each output line: kind \t tz \t a \t b   (no tabs or newlines inside fields).
"""
import re, sys, glob, os

ref = sys.argv[1]
strs, fmts, mods, intervals, zones = set(), set(), set(), set(), set()

def ok(s):
    return s is not None and '\t' not in s and '\n' not in s and '\\' not in s and len(s) < 200

for f in glob.glob(os.path.join(ref, 'timelib/tests/c/*.cpp')):
    src = open(f, encoding='latin-1').read()
    for m in re.finditer(r'test_parse\(\s*"((?:[^"\\]|\\.)*)"', src):
        if ok(m.group(1)): strs.add(m.group(1))
    for m in re.finditer(r'test_parse\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)', src):
        if 'from_format' in f and ok(m.group(1)) and ok(m.group(2)): fmts.add((m.group(1), m.group(2)))

q = r"'([^'\\$]*)'"
for f in glob.glob(os.path.join(ref, 'php-src/ext/date/tests/*.phpt')):
    src = open(f, encoding='latin-1').read()
    for pat in (r'strtotime\(\s*' + q, r'date_create(?:_immutable)?\(\s*' + q,
                r'new\s+DateTime(?:Immutable)?\(\s*' + q, r'date_parse\(\s*' + q):
        for m in re.finditer(pat, src):
            if ok(m.group(1)): strs.add(m.group(1))
    for m in re.finditer(r'(?:->modify|date_modify\(\s*\$\w+\s*,)\(?\s*' + q, src):
        if ok(m.group(1)): mods.add(m.group(1))
    for m in re.finditer(r'(?:createFromFormat|date_parse_from_format|date_create_from_format)\(\s*' + q + r'\s*,\s*' + q, src):
        if ok(m.group(1)) and ok(m.group(2)): fmts.add((m.group(1), m.group(2)))
    for m in re.finditer(r'new\s+DateInterval\(\s*' + q, src):
        if ok(m.group(1)): intervals.add(m.group(1))
    for m in re.finditer(r'new\s+DateTimeZone\(\s*' + q, src):
        if ok(m.group(1)): zones.add(m.group(1))

# Formats WordPress produces and parses, and common relative forms.
strs.update([
    '2024-01-02 03:04:05', '2024-01-02T03:04:05Z', '2024-01-02T03:04:05+05:30', '2024-01-02T03:04:05.123456-08:00',
    '0000-00-00 00:00:00', '2024-02-29 12:00:00', '2023-02-29', 'Tue, 02 Jan 2024 03:04:05 +0000',
    'Tue, 02 Jan 2024 03:04:05 GMT', '@1700000000', '@-1', 'now', 'today', 'tomorrow', 'yesterday', 'midnight', 'noon',
    'tomorrow noon', '+1 day', '-1 week', '+1 month', 'last day of next month', 'first day of this month',
    'first monday of january 2025', 'next monday', 'last friday', 'monday next week', '+1 week 2 days 4 hours 2 seconds',
    '1 year ago', '3 days ago 14:00', 'saturday this week', 'this sunday', '10 September 2000', 'Sep 10, 2000',
    '2000-09-10 +2 days', '20240102T030405', '2024W01', '2024-W01-3', '2024.123', '1/2/2024', '2/30/2024', '24:00',
    '12am', '12:30pm', 'EST', 'Europe/Berlin', '2024-01-02 03:04:05 Europe/Berlin', '2024-01-02 03:04:05 CEST',
    'back of 7pm', 'front of 7pm', '+1 weekday', '-3 weekdays', 'last day of february', 'first sat of July 2008',
    'garbage', '', '   ', '2024-13-45', '9999-12-31 23:59:59', '-0001-01-01', '+10000-01-01',
])
mods.update(['+1 day', '-1 month', 'last day of this month', 'first day of next month', 'midnight', 'noon', '+90 minutes',
             'next weekday', '+1 month -1 day', 'sunday', '@0', '+0 day', 'last day of previous month'])
fmts.update([('Y-m-d H:i:s', '2024-01-02 03:04:05'), ('Y-m-d', '2024-01-02'), ('!Y-m-d', '2024-01-02'),
             ('U', '1700000000'), ('d/m/Y H:i', '02/01/2024 03:04'), ('Y-m-d\\TH:i:sP', '2024-01-02T03:04:05+05:30')])
intervals.update(['P1D', 'PT1H', 'P1Y2M3DT4H5M6S', 'P2W', 'PT36H', 'P1M', 'P0D', 'PT0S'])

tzs = ['UTC', 'Europe/Berlin', 'America/New_York', 'Asia/Kolkata', 'Australia/Lord_Howe', 'America/Sao_Paulo']
bases = ['1700000000', '1711846800', '0', '-86400', '1730595600']   # 2023-11-14, EU DST start, epoch, 1969, US DST end
dt_bases = ['2024-01-31 10:00:00', '2024-03-31 01:30:00', '2024-11-03 01:30:00', '2023-02-28 23:59:59']
diff_pairs = [('2024-01-31', '2024-03-01'), ('2024-03-30 12:00', '2024-03-31 12:00'), ('2000-02-29', '2024-02-28'),
              ('2024-11-03 00:00', '2024-11-03 03:00'), ('2024-01-01', '2023-12-31 23:59:59.5'), ('1970-01-01', '2038-01-19 03:14:08')]
date_fmts = ['d D j l N S w z W F m M n t L o Y y a A B g G h H i s u v e I O P p T Z c r U',
             'jS \\o\\f F Y, g:i a', 'D, d M Y H:i:s O', 'Y-m-d\\TH:i:sP']

out = []
for s in sorted(strs):
    out.append(('date_parse', 'UTC', s, ''))
    for tz in tzs[:3]:
        for b in bases[:2]:
            out.append(('strtotime', tz, s, b))
    out.append(('create', 'Europe/Berlin', s, ''))
for s in sorted(mods):
    for tz in tzs:
        for b in dt_bases:
            out.append(('modify', tz, b, s))
for f, s in sorted(fmts):
    out.append(('from_format', 'UTC', f, s))
    out.append(('create_from_format', 'America/New_York', f, s))
for tz in tzs:
    for a, b in diff_pairs:
        out.append(('diff', tz, a, b))
    for b in bases:
        for f in date_fmts:
            out.append(('date', tz, f, b))
for i in sorted(intervals):
    out.append(('interval', 'UTC', i, ''))
for z in sorted(zones | set(tzs) | {'+05:30', '-0800', 'EST', 'CEST', 'Not/AZone'}):
    out.append(('zone', 'UTC', z, ''))
for k in out:
    if all('\t' not in x and '\n' not in x for x in k):
        print('\t'.join(k))
