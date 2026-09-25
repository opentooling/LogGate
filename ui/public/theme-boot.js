// The theme chosen inside the application, if one was, applied before the
// page paints. Stored per browser by the application under the same key.
try {
  const theme = localStorage.getItem("loggate.theme");
  if (theme === "light" || theme === "dark") document.documentElement.dataset.theme = theme;
} catch {
  // Storage blocked: the system theme applies.
}
