package com.example.Project1.Service;

import com.example.Project1.Config.ReminderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Periodically claims and delivers due appointment reminders.
 *
 * The claim and delivery steps are deliberately separate transactions:
 * a slow HTTP call to Meta must not hold the database row lock.
 */
@Component
public class AppointmentReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderScheduler.class);

    private final AppointmentReminderService reminderService;
    private final ReminderProperties properties;

    public AppointmentReminderScheduler(AppointmentReminderService reminderService,
                                        ReminderProperties properties) {
        this.reminderService = reminderService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.reminders.poll-interval-ms:60000}")
    public void pollDueReminders() {
        if (!properties.isEnabled()) {
            return;
        }

        List<UUID> claimed = reminderService.claimDueReminders();
        for (UUID reminderId : claimed) {
            try {
                reminderService.deliverReminder(reminderId);
            } catch (Exception e) {
                // Keep one failure from stopping the rest of the batch.
                log.error("Unexpected error delivering reminder {}: {}", reminderId, e.getMessage(), e);
            }
        }
    }

    @Scheduled(cron = "${app.reminders.rebuild-cron:0 15 0 * * *}",
            zone = "${app.reminders.zone:America/Guayaquil}")
    public void rebuildUpcomingReminders() {
        if (!properties.isEnabled()) {
            return;
        }
        reminderService.rebuildUpcomingReminders();
    }
}
