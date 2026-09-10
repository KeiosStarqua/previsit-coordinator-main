package com.previsitcoordinator.intake;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Fixed local availability for the demo while no external appointment provider
 * is configured. Offers a small weekly timetable: several days, each with a few
 * bookable times, so staff can pick a slot from a schedule grid on the
 * dashboard. Slot IDs are derived deterministically from the start time so they
 * stay stable across restarts and across repeated availability lookups (a
 * proposed slot keeps the same ID when it is later confirmed).
 */
@Component
class MockAppointmentGateway implements AppointmentGateway {

    // Times are UTC; staff are in Singapore (UTC+8), so these read as
    // 09:00, 11:00 and 14:30 local -- ordinary clinic hours.
    private static final String[][] TIMES_UTC = {
            {"01:00:00", "01:30:00"},
            {"03:00:00", "03:30:00"},
            {"06:30:00", "07:00:00"},
    };
    private static final String[] DAYS_UTC = {
            "2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17",
    };
    private static final String[] PROVIDERS = {"Dr. Avery Chen", "Dr. Maya Rao"};

    private static final List<AppointmentSlot> AVAILABLE_SLOTS = buildSlots();

    private static List<AppointmentSlot> buildSlots() {
        List<AppointmentSlot> slots = new ArrayList<>();
        int i = 0;
        for (String day : DAYS_UTC) {
            for (String[] t : TIMES_UTC) {
                String startsAt = day + "T" + t[0] + "Z";
                String endsAt = day + "T" + t[1] + "Z";
                UUID slotId = UUID.nameUUIDFromBytes(("slot-" + startsAt).getBytes(StandardCharsets.UTF_8));
                slots.add(new AppointmentSlot(
                        slotId,
                        Instant.parse(startsAt),
                        Instant.parse(endsAt),
                        PROVIDERS[i % PROVIDERS.length],
                        "Demo Primary Care Clinic",
                        "Primary care visit"));
                i++;
            }
        }
        return List.copyOf(slots);
    }

    @Override
    public List<AppointmentSlot> findAvailableSlots(IntakeCase intakeCase) {
        return AVAILABLE_SLOTS;
    }

    @Override
    public Appointment bookConfirmedAppointment(IntakeCase intakeCase, AppointmentSlot slot) {
        return new Appointment(
                UUID.randomUUID(),
                slot,
                intakeCase.patientReference(),
                AppointmentStatus.SCHEDULED);
    }
}
