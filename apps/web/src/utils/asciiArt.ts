const ART_MAP: Record<string, string> = {
  '猫': `
  /\\_/\\  
 ( o.o ) 
  > ^ <
 /|   |\\
(_|   |_)
`.trimStart(),

  '猫娘': `
  ╱|、
 (˚ˎ 。7  
  |、˜〵          
 じしˍ,)ノ
`.trimStart(),

  'neko': `
  /\\_/\\  
 ( o.o ) 
  > ^ <
  /|   |\\
 (_|   |_)
 neko!
`.trimStart(),

  'happy': `
  \\   /
   .-.
  (   )
 -=o=-
  /|\\
   |
  ===
`.trimStart(),

  'love': `
   ♥
  /|\\
   |
  / \\
`.trimStart(),

  'star': `
   *
  /|\\
   |
  /|\\
   *
`.trimStart(),

  'hello': `
  _   _      _ _ 
 | | | | ___| | |
 | |_| |/ _ \\ | |
 |  _  |  __/_| |
 |_| |_|\\___(_)_|
`.trimStart(),

  'code': `
  ┌─────────────────┐
  │  CODE MODE ON   │
  │  >_             │
  └─────────────────┘
`.trimStart(),

  'coffee': `
       )  (
      (   ) )
       ) ( (
     _______)_
    |         |
    |  COFFEE  |
    |_________|
`.trimStart(),

  'rocket': `
      /\\
     /  \\
    / .. \\
   /  /\\  \\
  |  |  |  |
  |  |  |  |
  |__|_|_|_|
  /__|_|_|__\\
     /||\\
    / || \\
      ||
     /  \\
    /____\\
`.trimStart(),

  'music': `
   ♪ ♫ ♪ ♫
  ┌─────────┐
  │ ~~~~~~~ │
  │  Music  │
  │ ~~~~~~~ │
  └─────────┘
   ♫ ♪ ♫ ♪
`.trimStart(),

  'fire': `
    (  )
   (    )
  (      )
 (   )(   )
(  (    )  )
 (  )  (  )
  (    )
   (  )
    ()
`.trimStart(),

  'heart': `
   ♥   ♥
  ♥ ♥ ♥ ♥
  ♥  ♥  ♥
   ♥   ♥
    ♥ ♥
     ♥
`.trimStart(),

  'sun': `
   \\   /
    .-.
 --(   )--
    '-'
   /   \\
`.trimStart(),

  'moon': `
     _..._
   .:::::::.
  :::::::::::
  :::::::::::
  '::::::::::'
    '::::::::'
      '::::'
        '::'
`.trimStart(),

  'tree': `
       *
      /|\\
     / | \\
    /  |  \\
   /   |   \\
  /____|____\\
       |
    ___|___
`.trimStart(),

  'house': `
       /\\
      /  \\
     /    \\
    /______\\
   |  ____  |
   | |    | |
   | | [] | |
   |_|____|_|
`.trimStart(),

  'car': `
     ______
    /|_||_\\'.___
   (   _    _ _\\
   =\`-(_)--(_)-'
`.trimStart(),

  'phone': `
   .-------.
   |  ____  |
   | |    | |
   | |____| |
   |  ____  |
   | |    | |
   | |____| |
   |       |
   '-------'
`.trimStart(),

  'game': `
   .-------.
   |  ___  |
   | |   | |
   | | ● | |
   | |___| |
   |_______|
   | A B X |
   '-------'
`.trimStart(),

  'book': `
   _______________
  |  ___________  |
  | |           | |
  | |   BOOK    | |
  | |           | |
  | |___________| |
  |_______________|
`.trimStart(),
}

export function detectAsciiArt(text: string): string | null {
  for (const [keyword, art] of Object.entries(ART_MAP)) {
    if (text.toLowerCase().includes(keyword.toLowerCase())) {
      return art
    }
  }
  return null
}
