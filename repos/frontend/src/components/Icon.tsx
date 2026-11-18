import { ICONS, type IconName, type IconPath } from './icons'

interface IconProps {
  name: IconName
  size?: number
  className?: string
}

/** 24x24 규격 인라인 SVG 아이콘. 색은 currentColor 를 따른다. */
export default function Icon({ name, size = 20, className = '' }: IconProps) {
  // ICONS 는 satisfies 로 키를 좁혀 두어 항목마다 형태가 갈린다 — 공통 타입으로 받는다.
  const paths: readonly IconPath[] = ICONS[name]
  if (!paths) return null

  return (
    <svg
      className={`ic ${className}`.trim()}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      style={{ display: 'inline-block', flexShrink: 0 }}
    >
      {paths.map((p, i) => (
        <path
          key={i}
          d={p.d}
          transform={p.x || p.y ? `translate(${p.x ?? 0} ${p.y ?? 0})` : undefined}
          fill="currentColor"
          fillRule={p.rule === 'evenodd' ? 'evenodd' : 'nonzero'}
          clipRule={p.rule === 'evenodd' ? 'evenodd' : undefined}
        />
      ))}
    </svg>
  )
}
