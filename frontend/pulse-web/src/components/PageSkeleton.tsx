export function PageSkeleton() {
  return (
    <div className="flex min-h-screen flex-col gap-6 bg-background px-6 py-12">
      <div className="mx-auto h-16 w-16 animate-pulse rounded-full bg-white/10" />
      <div className="mx-auto h-6 w-48 animate-pulse rounded-full bg-white/10" />
      <div className="mx-auto h-4 w-32 animate-pulse rounded-full bg-white/5" />
      <div className="mx-auto grid w-full max-w-5xl grid-cols-1 gap-4 md:grid-cols-3">
        {Array.from({ length: 6 }).map((_, index) => (
          <div key={index} className="h-28 animate-pulse rounded-2xl bg-white/5" />
        ))}
      </div>
    </div>
  );
}
