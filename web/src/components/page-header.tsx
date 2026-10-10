export function PageHeader({
  title,
  description,
  actions,
  eyebrow,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  eyebrow?: React.ReactNode;
}) {
  return (
    <div className="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        {eyebrow ? (
          <div className="eyebrow mb-4 flex items-center gap-3">
            <span className="h-px w-6 bg-accent-fg" aria-hidden />
            {eyebrow}
          </div>
        ) : null}
        <h1 className="font-display text-4xl leading-[1.05] md:text-5xl">{title}</h1>
        {description ? <p className="mt-3 max-w-2xl text-lg text-ink-strong">{description}</p> : null}
      </div>
      {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
    </div>
  );
}
