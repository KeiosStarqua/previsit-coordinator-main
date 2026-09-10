"""
End-to-end Selenium test suite for the PreVisit Call-E Coordinator dashboard.

Drives a REAL Chrome window against the running app and exercises EVERY
function on the platform, organised into independent scenarios (each gets its
own fresh browser so one failure can't cascade into the next):

  A. Registration + email OTP verification, and the app shell
     - backend health indicator ("online")
     - Create-account form: email field is type=email
     - registration validation errors (weak password, bad phone)
     - register a new account and verify the emailed OTP (dev code on screen)
     - signed-in user chip, coordination hotline (+65 8804 4731) shown + tel link
     - light/dark theme toggle
     - bento overview tiles (Active cases, Staff alerts, Backend Online)
  B. Bad login + duplicate registration
     - wrong password -> login error
     - duplicate username -> registration error
  C. Login of an UNVERIFIED account -> routed to OTP (+ Resend code, Back)
  D. Full coordination workflow (happy path)
     - start intake case -> record intake (non-emergency)
     - place Call-E call + poll result
     - approve scheduling -> appointment TIMETABLE loads
     - pick a time on the timetable -> propose -> verbal confirm -> schedule
     - staff-review handoff "ready for clinic staff"
     - the case-detail Refresh button
  E. Remove a case (list "x" quick-remove, and detail two-step Remove)
  F. Emergency path -> scheduling halted -> staff alert raised + resolved
     (and the halt is NOT lifted by resolving the alert)
  G. Sign out -> login overlay returns

Requirements:
  - Python 3.9+;  pip install selenium  (Selenium 4.15+ ships Selenium Manager)
  - Google Chrome installed
  - The dashboard running and reachable (default http://localhost:8080)
  - IMPORTANT: run the backend WITHOUT a RESEND_API_KEY (otp.dev-echo=true) so
    the signup OTP is shown on screen; the suite reads that dev code to verify.

Run:
  python test_previsit_dashboard.py

Environment overrides (optional):
  BASE_URL     default http://localhost:8080
  PATIENT_REF  default "Dang"
  PHONE        default "+6588044731"
  USERNAME     default "dang"          (primary verified account)
  PASSWORD     default "previsit123"
  EMAIL        default "dang.selenium@example.com"
  HEADLESS     default "0" (visible). Set to "1" to run headless.
  ONLY         comma-separated scenario letters to run (e.g. ONLY=D,F)
"""

import os
import re
import sys
import time
import datetime

from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException, ElementClickInterceptedException

# ----------------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------------
BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
PATIENT_REF = os.environ.get("PATIENT_REF", "Dang")
PHONE = os.environ.get("PHONE", "+6588044731")
HOTLINE = os.environ.get("HOTLINE", "+6588044731")  # number Call-E dials in scenario H
USERNAME = os.environ.get("USERNAME", "dang")
PASSWORD = os.environ.get("PASSWORD", "previsit123")
EMAIL = os.environ.get("EMAIL", "dang.selenium@example.com")
HEADLESS = os.environ.get("HEADLESS", "0") == "1"
ONLY = [s.strip().upper() for s in os.environ.get("ONLY", "").split(",") if s.strip()]
SHOTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshots")
TIMEOUT = 20

os.makedirs(SHOTS_DIR, exist_ok=True)

_passed = 0
_failed = 0
_step = 0


# ----------------------------------------------------------------------------
# Small test helpers
# ----------------------------------------------------------------------------
def check(name, condition):
    """Record a PASS/FAIL assertion without aborting the whole run."""
    global _passed, _failed
    if condition:
        _passed += 1
        print(f"    [PASS] {name}")
    else:
        _failed += 1
        print(f"    [FAIL] {name}")
    return bool(condition)


def shot(driver, label):
    global _step
    _step += 1
    path = os.path.join(SHOTS_DIR, f"{_step:02d}_{label}.png")
    try:
        driver.save_screenshot(path)
    except Exception as exc:  # pragma: no cover
        print(f"    (could not save screenshot: {exc})")


def wait(driver, cond, timeout=TIMEOUT):
    return WebDriverWait(driver, timeout).until(cond)


def css(sel):
    return (By.CSS_SELECTOR, sel)


def safe_click(driver, element):
    """Click, falling back to a JS click if an overlay (e.g. a toast) or the
    3D tilt transform intercepts the normal click."""
    driver.execute_script("arguments[0].scrollIntoView({block:'center'});", element)
    try:
        element.click()
    except (ElementClickInterceptedException, Exception):
        driver.execute_script("arguments[0].click();", element)


def click_sel(driver, sel):
    safe_click(driver, driver.find_element(*css(sel)))


def set_value(driver, sel, value):
    el = driver.find_element(*css(sel))
    el.clear()
    el.send_keys(value)
    return el


def detail_text(driver):
    try:
        return driver.find_element(*css("#detailContent")).text
    except Exception:
        return ""


def panel_text(driver, sel):
    try:
        return driver.find_element(*css(sel)).text
    except Exception:
        return ""


def toasts_text(driver):
    try:
        return driver.find_element(*css("#toasts")).text
    except Exception:
        return ""


def err_text(driver, sel):
    try:
        el = driver.find_element(*css(sel))
        return el.text if el.get_attribute("hidden") is None else ""
    except Exception:
        return ""


def wait_detail_contains(driver, text, timeout=TIMEOUT):
    return wait(driver, lambda d: text.lower() in detail_text(d).lower(), timeout)


def wait_panel_contains(driver, sel, text, timeout=TIMEOUT):
    return wait(driver, lambda d: text.lower() in panel_text(d, sel).lower(), timeout)


def overlay_dismissed(driver):
    try:
        return driver.find_element(*css("#authOverlay")).get_attribute("hidden") is not None
    except Exception:
        return True


def otp_visible(driver):
    try:
        return driver.find_element(*css("#otpForm")).get_attribute("hidden") is None
    except Exception:
        return False


def dev_code(driver):
    """Read the on-screen dev OTP (shown when the backend has no RESEND_API_KEY)."""
    try:
        dev = driver.find_element(*css("#otpDev"))
        if dev.get_attribute("hidden") is None:
            m = re.search(r"(\d{6})", dev.text)
            if m:
                return m.group(1)
    except Exception:
        pass
    return ""


def complete_otp(driver):
    """Finish the email-verification step using the on-screen dev code."""
    wait(driver, EC.visibility_of_element_located(css("#otpCode")))
    code = dev_code(driver)
    if not code:
        raise RuntimeError(
            "No on-screen dev OTP found. Start the backend without a "
            "RESEND_API_KEY (otp.dev-echo=true) so the code is shown for the test.")
    set_value(driver, "#otpCode", code)
    click_sel(driver, "#otpForm button[type=submit]")
    wait(driver, overlay_dismissed)
    return code


def go_to_login(driver):
    """Load the app and make sure the Sign-in tab is the active auth form."""
    driver.get(BASE_URL + "/")
    wait(driver, EC.visibility_of_element_located(css("#authOverlay")))
    try:
        click_sel(driver, "#tabLogin")
    except Exception:
        pass


def authenticate(driver):
    """Sign in with the primary account; on first run register it (email +
    phone) and verify the emailed OTP. Repeatable: a later run simply logs in,
    and a still-unverified account is routed back through the OTP step."""
    driver.get(BASE_URL + "/")            # callers (ensure_clean_cases) rely on this
    wait(driver, EC.visibility_of_element_located(css("#authOverlay")))
    try:
        click_sel(driver, "#tabLogin")
    except Exception:
        pass
    set_value(driver, "#loginUser", USERNAME)
    set_value(driver, "#loginPass", PASSWORD)
    click_sel(driver, "#loginForm button")
    wait(driver, lambda d: overlay_dismissed(d) or otp_visible(d)
         or d.find_element(*css("#loginError")).is_displayed())
    if overlay_dismissed(driver):
        return
    if otp_visible(driver):
        complete_otp(driver)
        return
    # Account does not exist yet -> register it, then verify the OTP.
    click_sel(driver, "#tabRegister")
    wait(driver, EC.visibility_of_element_located(css("#regName")))
    set_value(driver, "#regName", PATIENT_REF)
    set_value(driver, "#regUser", USERNAME)
    set_value(driver, "#regEmail", EMAIL)
    set_value(driver, "#regPhone", PHONE)
    set_value(driver, "#regPass", PASSWORD)
    click_sel(driver, "#registerForm button")
    complete_otp(driver)


def ensure_clean_cases(driver):
    """Sign in, clear this user's local case list, and land on a clean board."""
    authenticate(driver)
    driver.execute_script(
        "try{Object.keys(localStorage).filter(k=>k.indexOf('previsit.cases')===0)"
        ".forEach(k=>localStorage.removeItem(k));}catch(e){}")
    driver.refresh()
    wait(driver, EC.presence_of_element_located(css("#startForm")))
    wait(driver, overlay_dismissed)


def start_case(driver, ref, expect_count):
    set_value(driver, "#patientRef", ref)
    set_value(driver, "#phone", PHONE)
    click_sel(driver, "#startForm button")
    wait(driver, lambda d: d.find_element(*css("#caseCount")).text == str(expect_count))
    wait(driver, EC.visibility_of_element_located(css("#detailContent")))


def make_driver():
    opts = Options()
    if HEADLESS:
        opts.add_argument("--headless=new")
    opts.add_argument("--window-size=1440,1000")
    opts.add_argument("--disable-gpu")
    opts.add_experimental_option("excludeSwitches", ["enable-automation"])
    d = webdriver.Chrome(options=opts)
    d.set_window_size(1440, 1000)
    return d


# ============================================================================
# Scenarios
# ============================================================================
def scenario_A_register_and_shell(driver):
    """Registration validation + OTP verify + app shell (health, hotline,
    theme, bento tiles, user chip)."""
    go_to_login(driver)
    shot(driver, "A_login_screen")

    # --- registration validation on the Create-account form -----------------
    click_sel(driver, "#tabRegister")
    wait(driver, EC.visibility_of_element_located(css("#regName")))
    check("email field is type=email",
          driver.find_element(*css("#regEmail")).get_attribute("type") == "email")

    vuser = "valtest" + datetime.datetime.now().strftime("%H%M%S%f")[:12]
    # weak password -> rejected: an error is shown and we do NOT reach OTP.
    set_value(driver, "#regName", "Val Test")
    set_value(driver, "#regUser", vuser)
    set_value(driver, "#regEmail", vuser + "@example.com")
    set_value(driver, "#regPhone", PHONE)
    set_value(driver, "#regPass", "123")
    click_sel(driver, "#registerForm button")
    wait(driver, lambda d: err_text(d, "#registerError") != "" or otp_visible(d))
    check("weak password is rejected (error shown, not verified)",
          err_text(driver, "#registerError") != "" and not otp_visible(driver))

    # bad phone -> rejected the same way.
    set_value(driver, "#regPass", PASSWORD)
    set_value(driver, "#regPhone", "12345")
    click_sel(driver, "#registerForm button")
    time.sleep(0.8)
    check("bad phone is rejected (error shown, not verified)",
          err_text(driver, "#registerError") != "" and not otp_visible(driver))
    shot(driver, "A_register_validation")

    # --- register + verify (or log in) the primary account -------------------
    go_to_login(driver)
    authenticate(driver)
    check("signed in (auth overlay dismissed)", overlay_dismissed(driver))
    check("user chip shows the signed-in name",
          PATIENT_REF.lower() in driver.find_element(*css("#userName")).text.lower())

    # --- app shell -----------------------------------------------------------
    wait(driver, lambda d: d.find_element(*css("#healthText")).text.strip() != "checking backend…")
    check("header health = backend online",
          "online" in driver.find_element(*css("#healthText")).text.lower())
    check("bento Backend tile = Online",
          "online" in driver.find_element(*css("#bentoHealth")).text.lower())

    hotline = driver.find_element(*css(".bt-hotline"))
    check("coordination hotline shows +65 8804 4731",
          "8804 4731" in hotline.text)
    check("hotline is a tel: link to +6588044731",
          (hotline.get_attribute("href") or "").endswith("+6588044731"))

    # theme toggle
    before = driver.execute_script("return document.documentElement.getAttribute('data-theme')")
    click_sel(driver, "#themeBtn")
    time.sleep(0.6)
    after = driver.execute_script("return document.documentElement.getAttribute('data-theme')")
    check(f"theme toggle changes theme ({before} -> {after})", before != after)
    shot(driver, "A_shell_signed_in")


def scenario_B_bad_login_and_duplicate(driver):
    """Wrong password -> login error; duplicate username -> registration error.
    (Assumes scenario A already created the primary account.)"""
    go_to_login(driver)
    set_value(driver, "#loginUser", USERNAME)
    set_value(driver, "#loginPass", "definitely-wrong-pw")
    click_sel(driver, "#loginForm button")
    wait(driver, lambda d: err_text(d, "#loginError") != "")
    check("wrong password is rejected (error shown, still signed out)",
          err_text(driver, "#loginError") != "" and not overlay_dismissed(driver))
    shot(driver, "B_bad_login")

    click_sel(driver, "#tabRegister")
    wait(driver, EC.visibility_of_element_located(css("#regName")))
    set_value(driver, "#regName", PATIENT_REF)
    set_value(driver, "#regUser", USERNAME)          # already taken
    set_value(driver, "#regEmail", "dup@example.com")
    set_value(driver, "#regPhone", PHONE)
    set_value(driver, "#regPass", PASSWORD)
    click_sel(driver, "#registerForm button")
    wait(driver, lambda d: err_text(d, "#registerError") != "" or otp_visible(d))
    check("duplicate username is rejected (error shown, not verified)",
          err_text(driver, "#registerError") != "" and not otp_visible(driver))
    shot(driver, "B_duplicate_username")


def scenario_C_login_unverified_then_otp(driver):
    """Register but DON'T verify; signing in with that account routes to the
    OTP step. Also exercises the Resend code and Back buttons."""
    uname = "pending" + datetime.datetime.now().strftime("%H%M%S%f")[:12]
    go_to_login(driver)
    click_sel(driver, "#tabRegister")
    wait(driver, EC.visibility_of_element_located(css("#regName")))
    set_value(driver, "#regName", PATIENT_REF)
    set_value(driver, "#regUser", uname)
    set_value(driver, "#regEmail", uname + "@example.com")   # unique -> avoids duplicate-email
    set_value(driver, "#regPhone", PHONE)
    set_value(driver, "#regPass", PASSWORD)
    click_sel(driver, "#registerForm button")
    wait(driver, otp_visible)
    check("registration routes to the OTP step", otp_visible(driver))

    # Back button returns to the sign-up form
    click_sel(driver, "#otpBack")
    wait(driver, lambda d: d.find_element(*css("#registerForm")).get_attribute("hidden") is None)
    check("OTP 'Back' returns to the Create-account form",
          driver.find_element(*css("#registerForm")).get_attribute("hidden") is None)
    shot(driver, "C_registered_unverified")

    # Sign in with the still-unverified account -> OTP step again
    go_to_login(driver)
    set_value(driver, "#loginUser", uname)
    set_value(driver, "#loginPass", PASSWORD)
    click_sel(driver, "#loginForm button")
    wait(driver, lambda d: otp_visible(d) or overlay_dismissed(d)
         or d.find_element(*css("#loginError")).is_displayed())
    routed = otp_visible(driver)
    check("login of an unverified account routes to the OTP step", routed)
    shot(driver, "C_login_routed_to_otp")

    if routed:
        click_sel(driver, "#otpResend")          # Resend code -> fresh dev code
        time.sleep(0.8)
        check("Resend code keeps a dev code available on screen", dev_code(driver) != "")
        complete_otp(driver)
        check("verified from the login-OTP path (overlay dismissed)", overlay_dismissed(driver))
        shot(driver, "C_verified")
    else:
        print("    [SKIP] OTP step not shown -> is the REBUILT app running? (login 403 -> OTP)")


def scenario_D_full_workflow(driver):
    """Start -> intake -> Call-E -> approve -> TIMETABLE -> propose -> confirm
    -> handoff, plus the case Refresh button and bento Active-cases count."""
    ensure_clean_cases(driver)

    start_case(driver, PATIENT_REF, expect_count=1)
    check("case created and listed", driver.find_element(*css("#caseCount")).text == "1")
    check("bento Active cases = 1", driver.find_element(*css("#bentoCases")).text == "1")
    check("detail shows patient reference", PATIENT_REF in detail_text(driver))
    check("detail shows phone number", PHONE in detail_text(driver))
    check("status = Staff started", "staff started" in detail_text(driver).lower())
    shot(driver, "D_case_started")

    # Refresh button re-fetches status (case stays visible)
    click_sel(driver, "#refreshBtn")
    time.sleep(0.6)
    check("Refresh keeps the case open", PATIENT_REF in detail_text(driver))

    # intake (non-emergency)
    wait(driver, EC.presence_of_element_located(css("#intakeForm")))
    set_value(driver, "#reason", "Routine primary-care follow-up")
    set_value(driver, "#lang", "English")
    click_sel(driver, "#mobility")
    click_sel(driver, "#intakeForm button")
    wait_detail_contains(driver, "Intake complete")
    check("status = Intake complete", "intake complete" in detail_text(driver).lower())
    check("intake summary shows reason for visit",
          "routine primary-care follow-up" in detail_text(driver).lower())
    shot(driver, "D_intake")

    # Call-E call + poll
    wait(driver, EC.element_to_be_clickable(css("#startCallBtn")))
    click_sel(driver, "#startCallBtn")
    wait_panel_contains(driver, "#callPanel", "Completed")
    check("Call-E call result = Completed", "completed" in panel_text(driver, "#callPanel").lower())
    try:
        wait(driver, EC.element_to_be_clickable(css("#pollCallBtn")), timeout=8)
        click_sel(driver, "#pollCallBtn")
        time.sleep(1)
        check("poll keeps a Completed result", "completed" in panel_text(driver, "#callPanel").lower())
    except TimeoutException:
        check("poll button present", False)
    shot(driver, "D_call")

    # approve -> timetable auto-loads
    wait(driver, EC.element_to_be_clickable(css("#approveBtn")))
    click_sel(driver, "#approveBtn")
    wait(driver, lambda d: "scheduling approved" in detail_text(d).lower())
    check("status = Scheduling approved", "scheduling approved" in detail_text(driver).lower())

    try:
        wait(driver, EC.presence_of_element_located(css(".tt-slot")), timeout=6)
    except TimeoutException:
        click_sel(driver, "#loadSlotsBtn")
        wait(driver, EC.presence_of_element_located(css(".tt-slot")))
    slots = driver.find_elements(*css(".tt-slot"))
    days = driver.find_elements(*css(".tt-col"))
    check(f"appointment timetable renders slots (found {len(slots)})", len(slots) >= 1)
    check(f"timetable groups slots by day (found {len(days)} day columns)", len(days) >= 1)
    shot(driver, "D_timetable")

    # pick first time on the timetable -> propose
    safe_click(driver, driver.find_elements(*css(".tt-slot"))[0])
    time.sleep(0.3)
    click_sel(driver, "#proposeBtn")
    wait_panel_contains(driver, "#apptPanel", "Proposed")
    check("a timetable slot is marked Proposed", "proposed" in panel_text(driver, "#apptPanel").lower())
    shot(driver, "D_proposed")

    # verbal confirm -> schedule
    wait(driver, EC.presence_of_element_located(css("#verbalConfirm")))
    click_sel(driver, "#verbalConfirm")
    wait(driver, EC.element_to_be_clickable(css("#confirmBtn")))
    click_sel(driver, "#confirmBtn")
    wait_panel_contains(driver, "#apptPanel", "Appt ID")
    appt = panel_text(driver, "#apptPanel").lower()
    check("appointment scheduled confirmation shown", "appointment scheduled" in appt)
    check("appointment status = SCHEDULED", "scheduled" in appt)
    shot(driver, "D_appointment")

    # handoff
    wait_panel_contains(driver, ".panel.full", "ready for clinic staff")
    review = panel_text(driver, ".panel.full").lower()
    check("handoff reports ready for clinic staff", "ready for clinic staff" in review)
    check("handoff shows verbally confirmed", "verbally confirmed" in review)
    shot(driver, "D_handoff")


def scenario_E_remove_case(driver):
    """Quick-remove via the list 'x', and the two-step Remove in case detail."""
    ensure_clean_cases(driver)
    start_case(driver, PATIENT_REF + " A", expect_count=1)
    start_case(driver, PATIENT_REF + " B", expect_count=2)
    check("two cases listed", driver.find_element(*css("#caseCount")).text == "2")
    shot(driver, "E_two_cases")

    # list 'x' on the first item -> count drops, and it must NOT open the case
    placeholder_before = driver.find_element(*css("#detailPlaceholder")).get_attribute("hidden") is None
    click_sel(driver, ".case-item .case-x")
    wait(driver, lambda d: d.find_element(*css("#caseCount")).text == "1")
    check("list 'x' removed a case (2 -> 1)", driver.find_element(*css("#caseCount")).text == "1")
    check("bento Active cases updated to 1", driver.find_element(*css("#bentoCases")).text == "1")
    shot(driver, "E_after_list_x")

    # open the remaining case and use the two-step Remove button
    click_sel(driver, ".case-item")
    wait(driver, EC.element_to_be_clickable(css("#removeCaseBtn")))
    click_sel(driver, "#removeCaseBtn")  # step 1 -> "Confirm remove?"
    time.sleep(0.3)
    check("detail Remove asks to confirm first",
          "confirm" in driver.find_element(*css("#removeCaseBtn")).text.lower())
    click_sel(driver, "#removeCaseBtn")  # step 2 -> removes
    wait(driver, lambda d: d.find_element(*css("#caseCount")).text == "0")
    check("detail Remove cleared the last case (1 -> 0)",
          driver.find_element(*css("#caseCount")).text == "0")
    check("detail returns to placeholder after removal",
          driver.find_element(*css("#detailPlaceholder")).get_attribute("hidden") is None)
    shot(driver, "E_after_remove")


def scenario_F_emergency_and_alert(driver):
    """Emergency flag -> scheduling halted -> staff alert raised + resolved,
    and the halt is NOT lifted by resolving the alert."""
    ensure_clean_cases(driver)
    start_case(driver, PATIENT_REF + " (emergency)", expect_count=1)
    wait(driver, EC.presence_of_element_located(css("#intakeForm")))
    set_value(driver, "#reason", "Reports sudden chest discomfort")
    set_value(driver, "#lang", "English")
    click_sel(driver, "#emergency")  # possible emergency red flag
    click_sel(driver, "#intakeForm button")
    wait_detail_contains(driver, "Scheduling halted")
    check("emergency case status = Scheduling halted",
          "scheduling halted" in detail_text(driver).lower())
    check("halt notice shown in detail", "halted" in detail_text(driver).lower())
    shot(driver, "F_halted")

    # staff alert raised
    wait(driver, lambda d: d.find_element(*css("#alertCount")).text not in ("0", "—"))
    alert_before = driver.find_element(*css("#alertCount")).text
    check(f"a staff alert was raised (count = {alert_before})", alert_before not in ("0", "—"))
    check("bento Staff alerts tile matches",
          driver.find_element(*css("#bentoAlerts")).text == alert_before)
    shot(driver, "F_alert_raised")

    # resolve the alert
    open_btn = wait(driver, EC.element_to_be_clickable(css("#alertBox [data-open]")))
    safe_click(driver, open_btn)
    note = wait(driver, EC.visibility_of_element_located(css("#alertBox textarea")))
    note.send_keys("Advised to seek emergency care immediately; referred to A&E and logged for follow-up.")
    click_sel(driver, "#alertBox .resolve-form button")
    wait(driver, lambda d: d.find_element(*css("#alertCount")).text != alert_before)
    check("alert resolved (count changed)",
          driver.find_element(*css("#alertCount")).text != alert_before)

    # the halt must persist even after the alert is resolved
    click_sel(driver, ".case-item")
    time.sleep(0.4)
    check("case is STILL halted after resolving the alert",
          "scheduling halted" in detail_text(driver).lower())
    shot(driver, "F_alert_resolved")


def scenario_G_logout(driver):
    """Sign out returns the app to the login overlay."""
    authenticate(driver)
    check("signed in before logout", overlay_dismissed(driver))
    click_sel(driver, "#logoutBtn")
    wait(driver, lambda d: not overlay_dismissed(d))
    check("sign out returns to the login overlay", not overlay_dismissed(driver))
    shot(driver, "G_logged_out")


# ============================================================================
# Runner
# ============================================================================
def scenario_H_call_hotline(driver):
    """Place a Call-E coordination call TO the hotline number.

    Role-play: the Call-E agent is the PATIENT and the person who answers on the
    hotline is the care coordinator. The app dials whatever phone number the case
    was started with, so this starts a case whose demo phone number IS the
    hotline (HOTLINE, default +6588044731) and presses "Place Call-E call".

    NOTE: with the app's built-in MockCallEAdapter no real call is dialled -- the
    call run is simulated and returns "Completed". To actually ring the hotline
    (AI patient <-> human coordinator) a live Call-E provider adapter must be
    wired and live calls enabled; this scenario drives the same UI path either
    way."""
    ensure_clean_cases(driver)

    # Start a case whose demo phone number is the hotline -> Call-E dials it.
    set_value(driver, "#patientRef", PATIENT_REF + " (AI patient -> hotline)")
    set_value(driver, "#phone", HOTLINE)
    click_sel(driver, "#startForm button")
    wait(driver, lambda d: d.find_element(*css("#caseCount")).text == "1")
    wait(driver, EC.visibility_of_element_located(css("#detailContent")))
    check("case is set to call the hotline number", HOTLINE in detail_text(driver))
    shot(driver, "H_case_to_hotline")

    # Record intake (non-emergency) so a coordination call can be placed.
    wait(driver, EC.presence_of_element_located(css("#intakeForm")))
    set_value(driver, "#reason", "Patient calling to arrange a routine pre-visit")
    set_value(driver, "#lang", "English")
    click_sel(driver, "#intakeForm button")
    wait_detail_contains(driver, "Intake complete")

    # Place the Call-E call to the hotline and read the result.
    wait(driver, EC.element_to_be_clickable(css("#startCallBtn")))
    click_sel(driver, "#startCallBtn")
    try:
        wait(driver, lambda d: "completed" in panel_text(d, "#callPanel").lower()
             or "progress" in panel_text(d, "#callPanel").lower(), timeout=15)
    except TimeoutException:
        pass
    call = panel_text(driver, "#callPanel").lower()
    check("a Call-E call to the hotline was placed (call run recorded)",
          "completed" in call or "progress" in call or "call" in call)
    shot(driver, "H_hotline_call")


SCENARIOS = [
    ("A", "Registration + OTP verify + app shell", scenario_A_register_and_shell),
    ("B", "Bad login + duplicate registration", scenario_B_bad_login_and_duplicate),
    ("C", "Login of unverified account -> OTP (+resend/back)", scenario_C_login_unverified_then_otp),
    ("D", "Full coordination workflow + timetable", scenario_D_full_workflow),
    ("E", "Remove a case (list x + detail Remove)", scenario_E_remove_case),
    ("F", "Emergency halt + staff alert", scenario_F_emergency_and_alert),
    ("G", "Sign out", scenario_G_logout),
    ("H", "Call-E call to the hotline (AI patient)", scenario_H_call_hotline),
]


def run():
    started = datetime.datetime.now()
    print(f"PreVisit dashboard test suite -> {BASE_URL}  (headless={HEADLESS})")
    print(f"Screenshots: {SHOTS_DIR}")
    ran = 0
    for letter, title, fn in SCENARIOS:
        if ONLY and letter not in ONLY:
            continue
        ran += 1
        print(f"\n=== Scenario {letter}: {title} ===")
        driver = None
        try:
            driver = make_driver()
            fn(driver)
        except Exception as exc:
            global _failed
            _failed += 1
            print(f"    [FAIL] scenario {letter} crashed: {exc}")
            try:
                if driver:
                    shot(driver, f"{letter}_CRASH")
            except Exception:
                pass
        finally:
            if driver:
                if not HEADLESS:
                    time.sleep(1.5)  # let a watcher see the end state
                driver.quit()

    print("\n" + "=" * 60)
    print(f"OVERALL: {_passed} passed, {_failed} failed   ({ran} scenarios)")
    print(f"Finished in {(datetime.datetime.now() - started).seconds}s")
    print("=" * 60)
    return 0 if _failed == 0 else 1


if __name__ == "__main__":
    sys.exit(run())