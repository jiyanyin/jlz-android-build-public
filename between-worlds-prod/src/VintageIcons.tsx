import type { ReactNode } from "react";

/** Small, original vector counterparts to the approved blue-porcelain / antique-gold
 * icon art direction. Native artwork sheets remain separate design assets:
 * this lightweight set is bundled offline and stays legible in compact UI. */
export type VintageIconName =
  | "now" | "echo" | "between" | "jilinzhou" | "calendar" | "status"
  | "note" | "presence" | "todo" | "study" | "call" | "theme";

const cat = <g fill="#fbf2ea" stroke="#9585a8" strokeWidth="1">
  <path d="M25 44 Q19 36 23 31 L22 24 L29 28 L34 24 L38 31 Q42 41 34 45 Z" />
  <path d="M24 40Q17 45 23 48Q30 50 36 46" fill="none"/>
  <circle cx="28" cy="35" r=".8" fill="#524563" stroke="none"/>
  <circle cx="35" cy="35" r=".8" fill="#524563" stroke="none"/>
</g>;

function motif(name: VintageIconName): ReactNode {
  switch(name){
    case "now": return <><path d="M18 48V23Q32 7 46 23V48Z" fill="#304a72" stroke="#ddc18a" strokeWidth="2"/><path d="M24 48V27Q32 17 40 27V48" fill="#b7c5e6" stroke="#f9eacb"/><path d="M29 48V32L34 28L34 48" fill="#f3e0db"/><path d="M40 17l3 5 5 2-5 2-3 6-3-6-5-2 5-2z" fill="#ffe3ac" stroke="none"/></>;
    case "echo": return <><path d="M17 19Q30 12 46 21V40Q31 51 20 43L15 48L17 36Q12 26 17 19Z" fill="#c8d4ed" stroke="#f6e8c6" strokeWidth="2"/><path d="M23 30Q29 25 34 30T43 30" fill="none" stroke="#765f95" strokeWidth="2"/><circle cx="25" cy="35" r="2" fill="#b26a78"/><circle cx="32" cy="35" r="2" fill="#b26a78"/><circle cx="39" cy="35" r="2" fill="#b26a78"/></>;
    case "between": return <><path d="M32 44C20 35 15 30 19 23C23 16 30 19 32 24C37 16 46 17 47 25C48 33 40 39 32 44Z" fill="#d7a7bd" stroke="#f7e7c8" strokeWidth="2"/><path d="M17 36Q30 46 48 29" fill="none" stroke="#d7bb88" strokeWidth="2"/><circle cx="16" cy="36" r="3" fill="#e8d4f9" stroke="none"/></>;
    case "jilinzhou": return <><circle cx="32" cy="32" r="18" fill="#c2bfdc" stroke="#f4ddb0"/><path d="M18 47Q18 37 26 36L36 38Q44 39 47 47" fill="#263b57" stroke="#e8bf80" strokeWidth="1.5"/><path d="M25 28Q25 17 34 18Q42 19 39 29L36 36L30 38L26 33Z" fill="#f1d3c9" stroke="#8d7891"/><path d="M23 28Q23 16 35 16Q41 16 42 25Q34 21 28 25L27 32L24 32Z" fill="#273046" stroke="#d0baab"/><path d="M31 43l2 5 3-5" stroke="#edce95" fill="none"/></>;
    case "calendar": return <><rect x="18" y="19" width="28" height="29" rx="3" fill="#f8f0de" stroke="#b18f63" strokeWidth="2"/><path d="M18 28H46" stroke="#8a9fc0" strokeWidth="4"/><path d="M25 16v7m14-7v7" stroke="#c2a477" strokeWidth="3"/><circle cx="32" cy="37" r="7" fill="#d8dfec" stroke="#8e98ae"/><path d="M32 31v6l4 3" fill="none" stroke="#a97880" strokeWidth="1.5"/></>;
    case "status": return <><path d="M20 20H44L42 48H22Z" fill="#b7c2df" stroke="#dbc297" strokeWidth="2"/><path d="M24 26H40L37 43H27Z" fill="#273c5a" stroke="#f3e3cb"/><path d="M32 30L34 34L38 35L34 38L32 43L30 38L26 35L30 34Z" fill="#ffe3a1" stroke="none"/><path d="M21 19Q32 11 43 19" stroke="#ddbd83" fill="none" strokeWidth="3"/></>;
    case "note": return <><path d="M21 17H44V47H21Q17 45 19 41V22Z" fill="#f4ead8" stroke="#bda075" strokeWidth="2"/><path d="M25 23H39M25 28H38M25 34H36M25 40H35" stroke="#a0a8ba" strokeWidth="1.7"/><path d="M44 14L32 35L30 43L36 37L48 16Z" fill="#bfcee7" stroke="#c2a47f" strokeWidth="1.5"/></>;
    case "presence": return <><path d="M32 49Q16 34 21 24Q25 13 34 17Q45 19 43 30Q41 38 32 49Z" fill="#ccbedb" stroke="#f9e2b2" strokeWidth="2"/><circle cx="32" cy="28" r="7" fill="#344c77" stroke="#fff1d6"/><path d="M32 22V34M26 28H38" stroke="#f6d7a0" strokeWidth="1.7"/></>;
    case "todo": return <><rect x="19" y="18" width="27" height="31" rx="3" fill="#fff4df" stroke="#c5a276" strokeWidth="2"/>{[27,35,43].map((y,i)=><g key={i}><rect x="23" y={y-3} width="6" height="6" rx="1" fill="#b3c8df" stroke="#a58f80"/><path d={`M23 ${y}l2 2 4-5`} fill="none" stroke="#9a5d76" strokeWidth="1.7"/><path d={`M32 ${y}h10`} stroke="#a9a2a8" strokeWidth="1.4"/></g>)}</>;
    case "study": return <><path d="M17 25Q25 21 32 26Q40 21 47 25V44Q39 42 32 47Q25 42 17 44Z" fill="#f3ebd9" stroke="#bf9f77" strokeWidth="2"/><path d="M32 27V46M20 29Q26 28 29 31M35 31Q40 28 44 29" stroke="#b4a6aa" fill="none"/><path d="M20 25V18Q31 13 43 19L40 25" fill="#8fa8cb" stroke="#e3cb98" strokeWidth="1.5"/></>;
    case "call": return <><path d="M22 22C25 17 31 16 34 21L31 27C34 31 37 35 41 37L46 34Q52 38 45 44C34 53 17 34 19 27Z" fill="#f0e4d9" stroke="#c59b72" strokeWidth="2"/><circle cx="42" cy="19" r="4" fill="#af6882" stroke="none"/><path d="M42 13v-4M49 19h4" stroke="#dec792" strokeWidth="1.5"/></>;
    case "theme": return <><path d="M20 43V22L32 14L44 22V43Z" fill="#bec8e3" stroke="#d6ba87" strokeWidth="2"/><path d="M25 39Q25 23 32 21Q39 23 39 39Z" fill="#354b76"/><path d="M32 16L35 25L43 29L35 32L32 42L29 32L21 29L29 25Z" fill="#f2d9a4" stroke="none"/></>;
  }
}

export function VintageIcon({ name, size = 36 }: { name: VintageIconName; size?: number }) {
  return <svg className="vintage-icon" width={size} height={size} viewBox="0 0 64 64"
    aria-hidden="true" focusable="false" xmlns="http://www.w3.org/2000/svg">
    <circle cx="32" cy="32" r="29" fill="#faf1e2" stroke="#bc9769" strokeWidth="1.5"/>
    <circle cx="32" cy="32" r="26" fill="#d7e2f2" stroke="#e2cd9d" strokeWidth="1"/>
    <path d="M9 22Q8 9 22 8M42 8Q56 11 56 23M9 43Q8 55 22 56M42 56Q55 55 56 43"
      stroke="#e2c591" strokeWidth="1.5" fill="none"/>
    <path d="M11 32h4m34 0h4M32 10v4m0 36v4" stroke="#aa829a" strokeWidth="1"/>
    {motif(name)}
    {(name === "study" || name === "todo") && <g transform="translate(28 28) scale(.43)">{cat}</g>}
    <path d="M12 50Q24 46 32 52Q40 46 52 50" fill="none" stroke="#b78b9d" strokeWidth="2"/>
    <circle cx="13" cy="15" r="2" fill="#a95671" stroke="#f0dbbd"/>
    <circle cx="51" cy="46" r="2" fill="#a95671" stroke="#f0dbbd"/>
    <path d="M50 8l1.2 3 3 1.2-3 1.2-1.2 3-1.2-3-3-1.2 3-1.2z"
      fill="#e7c98d" stroke="none"/>
  </svg>;
}
