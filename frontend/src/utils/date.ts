/** 서울(KST) 기준 오늘 날짜를 YYYY-MM-DD로 돌려준다. 브라우저 시간대와 무관하게 실사용 기준일을 맞춘다. */
export function todayKst(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(now)
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')}`
}
