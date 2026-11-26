// 교대노트 캘린더 구독 워커 (Cloudflare Workers: proud-sun-54e5)
// 주소 형식: https://<워커>/<구독코드>/calendar.ics  또는  ?code=<구독코드>
//
// 끄거나(종료) 없어진 구독 코드에는 오류 대신 "빈 캘린더"를 돌려준다.
// → 캘린더 앱이 다음 갱신 때 예전 일정을 지운다. (오류를 주면 대부분의 캘린더 앱이 마지막 일정을 계속 보여줌)

const FIREBASE_PROJECT_ID = 'shift-schedule-01';
const ICS_HEADERS = {
  'Content-Type': 'text/calendar; charset=utf-8',
  'Cache-Control': 'no-cache',
};

export default {
  async fetch(request) {
    try {
      const url = new URL(request.url);
      let code = (url.searchParams.get('code') || '').trim().toUpperCase();
      if (!code) {
        // 쿼리스트링(?code=...) 대신 경로 맨 앞에 코드를 넣는 방식도 지원
        // 예: /T7YM9UQD/calendar.ics
        const pathParts = url.pathname.split('/').filter(Boolean);
        if (pathParts.length >= 1 && !pathParts[0].toLowerCase().endsWith('.ics')) {
          code = pathParts[0].toUpperCase();
        }
      }
      if (!code) {
        return new Response('사용법: 이 주소 뒤에 /구독코드/calendar.ics 를 붙여주세요 (앱의 "캘린더 구독"에서 만든 주소)', { status: 400 });
      }
      // 앱이 만드는 코드 형식(8자리, 헷갈리는 I·O·0·1 제외)이 아니면 조회하지 않고 빈 캘린더
      if (!/^[A-HJ-NP-Z2-9]{8}$/.test(code)) {
        return emptyCalendar();
      }

      const docUrl = `https://firestore.googleapis.com/v1/projects/${FIREBASE_PROJECT_ID}/databases/(default)/documents/shiftNoteShare/${code}`;
      const docRes = await fetch(docUrl);
      if (docRes.status === 404) {
        return emptyCalendar(); // 지워진 구독
      }
      if (!docRes.ok) {
        // 일시적인 서버 오류 등은 오류로 돌려줘서 캘린더 앱이 기존 일정을 유지하게 함 (잘못 지우지 않도록)
        return new Response(`데이터를 불러오지 못했어요 (상태: ${docRes.status}). 잠시 후 다시 시도됩니다.`, { status: 502 });
      }
      const doc = await docRes.json();
      const data = doc.fields ? decodeFirestoreFields(doc.fields) : {};

      // 종료된 구독(revoked), 내용이 없는 문서, 캘린더용이 아닌 문서는 빈 캘린더
      const allowedKinds = ['calendar_subscription_work', 'calendar_subscription_memo', 'share_link'];
      if (data.revoked === true || !data.payload || (data.kind && !allowedKinds.includes(data.kind))) {
        return emptyCalendar();
      }

      const ics = buildICS(data.payload, data.kind);
      return new Response(ics, { headers: ICS_HEADERS });
    } catch (e) {
      return new Response('오류가 발생했어요: ' + (e && e.message ? e.message : String(e)), { status: 500 });
    }
  },
};

// ---- 빈 캘린더 (종료·없는 구독용) ----
function emptyCalendar() {
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'METHOD:PUBLISH',
    'PRODID:-//ShiftNote//KR',
    'CALSCALE:GREGORIAN',
    'X-WR-CALNAME:교대노트 (종료된 구독)',
    'REFRESH-INTERVAL;VALUE=DURATION:PT6H',
    'X-PUBLISHED-TTL:PT6H',
    'END:VCALENDAR',
  ];
  return new Response(lines.map(foldLine).join('\r\n'), { headers: ICS_HEADERS });
}

// ---- Firestore REST 응답(타입이 붙은 JSON)을 평범한 JS 값으로 변환 ----
function decodeFirestoreValue(value) {
  if (!value) return null;
  if (value.nullValue !== undefined) return null;
  if (value.stringValue !== undefined) return value.stringValue;
  if (value.integerValue !== undefined) return parseInt(value.integerValue, 10);
  if (value.doubleValue !== undefined) return value.doubleValue;
  if (value.booleanValue !== undefined) return value.booleanValue;
  if (value.timestampValue !== undefined) return value.timestampValue;
  if (value.arrayValue !== undefined) return (value.arrayValue.values || []).map(decodeFirestoreValue);
  if (value.mapValue !== undefined) return decodeFirestoreFields(value.mapValue.fields || {});
  return null;
}
function decodeFirestoreFields(fields) {
  const out = {};
  for (const key in fields) out[key] = decodeFirestoreValue(fields[key]);
  return out;
}

// ---- 날짜 유틸 (Workers 런타임은 UTC 기준이라, 한국시간(UTC+9) 기준 "오늘"을 직접 계산) ----
function kstNow() {
  const utcNow = new Date();
  return new Date(utcNow.getTime() + 9 * 3600 * 1000); // 이 시각을 getUTC*로 읽으면 "한국시간 기준 값"이 나옴
}
function makeKstMidnight(y, m, d) {
  return new Date(Date.UTC(y, m, d, 0, 0, 0));
}
function addDays(date, n) {
  return new Date(date.getTime() + n * 86400000);
}
function diffDays(a, b) {
  return Math.round((a.getTime() - b.getTime()) / 86400000);
}
function isoDate(date) {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
function pad2(n) { return String(n).padStart(2, '0'); }

// 앱과 같은 기준: 기본 근무유형(locked)을 사용 안 함으로 꺼두면 표시하지 않음
function isTypeUsable(st) {
  return !(st.locked && st.enabled === false);
}

// ---- 근무표 패턴 계산 (앱의 shiftForTeamOnDate 로직과 같은 규칙) ----
function shiftForTeamOnDate(date, teamId, payload) {
  const iso = isoDate(date);
  const types = payload.shiftTypes || [];

  // 특정 날짜에 수동으로 지정해둔 값(대근/연차 등)이 있으면 항상 우선.
  // 새 앱은 근무유형 id로, 예전 앱·공유 데이터는 이름으로 저장하므로 둘 다 찾는다.
  const ov = payload.overrides && payload.overrides[iso] && payload.overrides[iso][teamId];
  if (ov) {
    const st = types.find(s => s.id === ov) || types.find(s => s.name === ov);
    if (st) return isTypeUsable(st) ? st : null;
    if (/^st\d+$/.test(ov)) return null; // 삭제된 근무유형을 가리키는 수정
    // 등록된 근무유형과 안 맞는 자유 입력 라벨은 그 이름 그대로 종일 일정으로 보여줌
    return { id: null, name: ov, role: 'other', color: '' };
  }

  const team = (payload.teams || []).find(t => t.id === teamId);
  if (!team || !team.sequence || team.sequence.length === 0) return null;

  const refDateStr = payload.referenceDate;
  if (!refDateStr) return null;
  const [ry, rm, rd] = refDateStr.split('-').map(Number);
  const refDate = makeKstMidnight(ry, rm - 1, rd);

  const L = team.sequence.length;
  const diff = diffDays(date, refDate);
  const idx = ((diff % L) + L) % L;
  const st = types.find(s => s.id === team.sequence[idx]);
  return st && isTypeUsable(st) ? st : null;
}

// ---- iCalendar 텍스트 한 줄 이스케이프 ----
function icsEscape(text) {
  return String(text).replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n');
}

function buildICS(payload, kind) {
  const now = kstNow();
  const y = now.getUTCFullYear(), m = now.getUTCMonth();
  const rangeStart = makeKstMidnight(y, m - 1, 1);       // 전달 1일
  const rangeEndExclusive = makeKstMidnight(y, m + 2, 1); // 익달 다음달 1일 (= 익달 말일 다음날)
  const totalDays = diffDays(rangeEndExclusive, rangeStart);

  // DTSTAMP는 "진짜" 현재 UTC 시각이어야 함 (kstNow()는 계산 편의를 위해 9시간 밀어둔 값이라 여기 쓰면 안 됨)
  const stampNow = new Date().toISOString().replace(/[-:]/g, '').split('.')[0] + 'Z';

  const calName = kind === 'calendar_subscription_memo' ? '교대노트 메모' : '교대노트 근무표';
  const lines = [];
  lines.push('BEGIN:VCALENDAR');
  lines.push('VERSION:2.0');
  lines.push('METHOD:PUBLISH');
  lines.push('PRODID:-//ShiftNote//KR');
  lines.push('CALSCALE:GREGORIAN');
  lines.push('X-WR-CALNAME:' + calName);
  lines.push('REFRESH-INTERVAL;VALUE=DURATION:PT6H');
  lines.push('X-PUBLISHED-TTL:PT6H');

  let uidCounter = 0;
  function pushEvent({ summary, startDate, endDate, allDay, alarmMinutesBefore }) {
    uidCounter++;
    lines.push('BEGIN:VEVENT');
    lines.push('UID:shiftnote-' + isoDate(startDate) + '-' + uidCounter + '@shiftnote');
    lines.push('DTSTAMP:' + stampNow);
    if (allDay) {
      lines.push('DTSTART;VALUE=DATE:' + isoDate(startDate).replace(/-/g, ''));
      lines.push('DTEND;VALUE=DATE:' + isoDate(endDate).replace(/-/g, ''));
    } else {
      // startDate/endDate는 "한국시간 값을 UTC 필드에 그대로 적어둔" 상태라, 진짜 UTC로 보내려면 9시간을 빼야 함.
      // VTIMEZONE 정의 없이 TZID만 참조하면 일부 캘린더(네이버 등)가 엄격하게 거부할 수 있어서 UTC(Z) 형식으로 통일.
      const toRealUtc = (d) => new Date(d.getTime() - 9 * 3600 * 1000);
      const fmt = (d) => {
        const u = toRealUtc(d);
        return isoDate(u).replace(/-/g, '') + 'T' + pad2(u.getUTCHours()) + pad2(u.getUTCMinutes()) + '00Z';
      };
      lines.push('DTSTART:' + fmt(startDate));
      lines.push('DTEND:' + fmt(endDate));
    }
    lines.push('SUMMARY:' + icsEscape(summary));
    if (alarmMinutesBefore !== undefined && alarmMinutesBefore !== null) {
      lines.push('BEGIN:VALARM');
      lines.push('ACTION:DISPLAY');
      lines.push('DESCRIPTION:' + icsEscape(summary));
      lines.push('TRIGGER:-PT' + Math.max(0, alarmMinutesBefore) + 'M');
      lines.push('END:VALARM');
    }
    lines.push('END:VEVENT');
  }

  for (let d = 0; d < totalDays; d++) {
    const date = addDays(rangeStart, d);
    const iso = isoDate(date);
    const mine = payload.myTeamId ? shiftForTeamOnDate(date, payload.myTeamId, payload) : null;

    if (mine && mine.role !== 'off' && mine.role !== 'standby') {
      const times = mine.id && payload.shiftTimes && payload.shiftTimes[mine.id];
      const alarm = mine.id && payload.alarms && payload.alarms[mine.id];

      if (times && times.start && times.end) {
        const [sh, sm] = times.start.split(':').map(Number);
        const [eh, em] = times.end.split(':').map(Number);
        const startDt = new Date(date.getTime());
        startDt.setUTCHours(sh, sm, 0, 0);
        let endDt = new Date(date.getTime());
        endDt.setUTCHours(eh, em, 0, 0);
        if (endDt <= startDt) endDt = addDays(endDt, 1); // 야간처럼 다음날로 넘어가는 근무

        let alarmMinutesBefore;
        if (alarm && alarm.enabled && alarm.time) {
          const [ah, am] = alarm.time.split(':').map(Number);
          const alarmDt = new Date(date.getTime());
          alarmDt.setUTCHours(ah, am, 0, 0);
          alarmMinutesBefore = Math.round((startDt.getTime() - alarmDt.getTime()) / 60000);
        }
        pushEvent({ summary: mine.name + ' 근무', startDate: startDt, endDate: endDt, allDay: false, alarmMinutesBefore });
      } else {
        pushEvent({ summary: mine.name, startDate: date, endDate: addDays(date, 1), allDay: true });
      }
    }

    const notesForDay = (payload.notes && payload.notes[iso]) || [];
    notesForDay.forEach((n) => {
      if (!n || !n.text) return;
      if (n.time) {
        const [nh, nm] = n.time.split(':').map(Number);
        const startDt = new Date(date.getTime());
        startDt.setUTCHours(nh, nm, 0, 0);
        const endDt = new Date(startDt.getTime() + 30 * 60000);
        pushEvent({ summary: n.text, startDate: startDt, endDate: endDt, allDay: false, alarmMinutesBefore: 10 });
      } else {
        pushEvent({ summary: n.text, startDate: date, endDate: addDays(date, 1), allDay: true });
      }
    });
  }

  lines.push('END:VCALENDAR');
  return lines.map(foldLine).join('\r\n');
}

// ---- RFC5545 줄 접기: 한 줄이 75옥텟(바이트)을 넘으면 이어지는 줄 맨 앞에 공백 하나를 붙여서 나눔.
// 한글은 UTF-8에서 한 글자에 3바이트라, 짧아 보이는 문장도 금방 75바이트를 넘을 수 있음.
function foldLine(line) {
  const bytes = new TextEncoder().encode(line);
  if (bytes.length <= 75) return line;

  const decoder = new TextDecoder();
  const chunks = [];
  let start = 0;
  let first = true;
  while (start < bytes.length) {
    const limit = first ? 75 : 74; // 이어지는 줄은 맨 앞 공백 1바이트를 포함해서 75가 되도록
    let end = Math.min(start + limit, bytes.length);
    while (end > start && (bytes[end] & 0xC0) === 0x80) end--; // 멀티바이트 문자 중간에서 안 잘리게 뒤로 물러남
    chunks.push((first ? '' : ' ') + decoder.decode(bytes.slice(start, end)));
    start = end;
    first = false;
  }
  return chunks.join('\r\n');
}
