document.querySelectorAll("form[data-once]").forEach((form) => {
  form.addEventListener("submit", () => {
    const button = form.querySelector("[type=submit]");
    if (button instanceof HTMLButtonElement) {
      button.disabled = true;
    }
  });
});

const destination = document.querySelector("[data-destination]");
const externalAccount = document.querySelector("[data-external]");
if (destination instanceof HTMLSelectElement && externalAccount instanceof HTMLElement) {
  const syncDestination = () => {
    externalAccount.hidden = destination.value !== "external";
  };
  destination.addEventListener("change", syncDestination);
  syncDestination();
}
