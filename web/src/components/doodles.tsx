/*
 * Hum decorative SVG system — hand-drawn marks (squiggle / sparkle / orbit stamp).
 * Every component is aria-hidden pure decoration; colours come from CSS classes
 * in styles.css (token-referenced), never inline values.
 */

export function Squiggle({ className = '' }: { className?: string }) {
  return (
    <svg
      aria-hidden="true"
      className={`doodle-squiggle ${className}`}
      viewBox="0 0 124 12"
      preserveAspectRatio="none"
      focusable="false"
    >
      <path
        d="M3 8.5 Q 13 2.5 23 8.5 T 43 8.5 T 63 8.5 T 83 8.5 T 103 8.5 T 121 8.5"
        pathLength={100}
      />
    </svg>
  );
}

export function Sparkle({
  className = '',
  delay = 0,
}: {
  className?: string;
  delay?: number;
}) {
  return (
    <svg
      aria-hidden="true"
      className={`doodle-sparkle ${className}`}
      style={delay ? { animationDelay: `${delay}ms` } : undefined}
      viewBox="0 0 24 24"
      focusable="false"
    >
      <path d="M12 1.8 14.6 9.4 22.2 12 14.6 14.6 12 22.2 9.4 14.6 1.8 12 9.4 9.4Z" />
    </svg>
  );
}

export function OrbitBadge({
  text,
  className = '',
}: {
  text: string;
  className?: string;
}) {
  return (
    <svg
      aria-hidden="true"
      className={`doodle-orbit ${className}`}
      viewBox="0 0 120 120"
      focusable="false"
    >
      <defs>
        <path
          id="doodle-orbit-circle"
          d="M60 60 m -42 0 a 42 42 0 1 1 84 0 a 42 42 0 1 1 -84 0"
          pathLength={100}
        />
      </defs>
      <g className="doodle-orbit-text">
        <text>
          <textPath href="#doodle-orbit-circle">{text}</textPath>
        </text>
      </g>
      <path
        className="doodle-orbit-core"
        d="M60 44 64.2 55.8 76 60 64.2 64.2 60 76 55.8 64.2 44 60 55.8 55.8Z"
      />
    </svg>
  );
}
