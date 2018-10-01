

func get_handling_provider(kind) {
	return provider_db.get(kind);
}

func invoke_functions(provider_desc, function_list, strategy, task, metadata) {
	// do rest calls to provider's using strategy while updating task
	// effects running on the provider will return their effect_id to be registered in the task

	// for sequential strategy where task fails on first failure
	
	res = rest.post(provider_desc.get(function_list[0]), get_entity(metadata));

	on_status_change(metadata, func(entity) {
		delta = delta(entity);
		if (!delta) {
			// we have converged
			task.success();
		}
		if (task.failed) {
			// this strategy stops on first failure
			task.fail();
		}
		else {
			function_list = analyze_function(delta);
			invoke_functions(provider_desc, function_list, strategy, task, metadata);
		}
		return;
	});
	
	task.update_worker_effect(res.effect_uuid);
	
}

