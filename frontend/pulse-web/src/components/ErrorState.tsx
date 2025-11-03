import { AlertTriangle } from "lucide-react";

type ErrorStateProps = {
  title: string;
  error?: unknown;
  onRetry?: () => void;
};

function getErrorMessage(error: unknown): string {
  if (!error) {
    return "Something went wrong.";
  }

  if (error instanceof Error) {
    return error.message;
  }

  if (typeof error === "string") {
    return error;
  }

  try {
    return JSON.stringify(error);
  } catch {
    return "Unexpected error.";
  }
}

export function ErrorState({ title, error, onRetry }: ErrorStateProps) {
  const message = getErrorMessage(error);

  return (
    <div className="min-h-screen bg-background px-6 py-12">
      <div className="mx-auto flex w-full max-w-lg flex-col items-center gap-6 rounded-3xl border border-white/10 bg-white/5 p-10 text-center shadow-2xl shadow-black/30 backdrop-blur">
        <div className="rounded-full border border-red-500/50 bg-red-500/10 p-4 text-red-300">
          <AlertTriangle className="h-8 w-8" />
        </div>
        <div className="space-y-2">
          <h2 className="text-2xl font-semibold text-white">{title}</h2>
          <p className="text-sm text-white/70">{message}</p>
        </div>
        {onRetry ? (
          <button
            type="button"
            onClick={onRetry}
            className="rounded-full bg-white/10 px-6 py-2 text-sm font-medium text-white transition hover:bg-white/20"
          >
            Try again
          </button>
        ) : null}
      </div>
    </div>
  );
}
