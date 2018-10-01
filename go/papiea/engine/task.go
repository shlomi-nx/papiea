// this is a Task_client to the task service

// Task manager needs to have a blob section which we will define here

blob = {failed: [{effects_name, [errors]}]}

// Task
func new_task(delta_spec, spec_version, effect_uuid[]) {
	// Store all these in the task
	task = task_service.new_task(Papiea.id, delta_spec, spec_version, effect_uuid);

	// register all listeners
	register_task(task);
	
	// start all the listeners
	map(task.listeners, start);
	// return the new task
	return task;
}


func register_task(task) {
	for (status_field : delta_spec) {
		task.listeners += Papiea.on(status_field, func(ctx context, e const entity) {
			if (e.status.status_field == status_field) {
				// this field has been met
				Papiea.stop_listening(ctx, this);
				check_task(task);
			} else (e.metadata.spec_version == spec_version) {
				// log that it is still in progress
				// if the handling effect is not running then re-run it
			}
			else (e.metadata.spec_version > spec_version) {
				// log failure due to "outdated spec change". Perhaps also log which spec change invalidated this one
				Papiea.stop_listening(ctx, this);
				check_task(task);
			}
		});
	}
}

func check_task(task) {
	if (remove_stopped(task.listeners) is empty) {
		if (has_failures(task) && has_success(task)) task.set_partial_success();
		else if (has_failures(task)) task.set_failed();
		else task.set_success();
	}
}

func is_task_working(task) {
	// Check with the relevant provider that at least one of the effects in task.effect_uuid list is still responding
}

func recover() {
	// In case papiea died and came back again.
	tasks = task_service.get_unfinished_tasks_assigned_to_me(Papiea.id);

	foreach (task : tasks) {
		register_task(task);
		map(task.listeners, start);task
	}
}

