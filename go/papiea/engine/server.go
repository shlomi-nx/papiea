api = new rest.api()

// Papiea exposes the following internal to the providers apis:
func update_spec(metadata, spec) task,err {
	// creates a task
	task = task_client.new_task(metadata, spec)
        // TODO: Lets think about tasks:
	//        1. Do we have 1 task per entity
	//        2. Do we use a task as a flag to know what operations died
	// tries to CAS in the spec
	if (old_spec = cas(metada, spec)) {
		task.update_delta(compute_delta(old_spec, spec))
		delta = compute_delta(spec, status)
		function_list = analyze_function(delta);
		provider.invoke_functions(function_list, strategy, task)
	}
}

func update_status(uuid, status) err {
	return status_db.update(uuid, status);
}

func get_entity(uuid) entity, err {
	return entity(spec_db.get(uuid), status_db.get(uuid));
}


// When a spec change arrives returns a task immediately
// 


// TODO: Think of the idea of storing spec deltas at times of CASing. This may allow us in the future, to define a dependcy tree, such that we could deduce which operations may run in parallel


// All Papieas will support all providers, segments could be defined by envoy routing
// Providers registration:
// Provider_description contains:
//  1. the url to get to it "https://ip:port/kind/v1/"
//  2. the yaml defining the entities handled by this provider

func register_provider(provider_description) {
	provider_db.upsert(provider_description);
}

func upgrade_provider(provider_description_from, provider_description_to) {
        provider_db.update(provider_description_from, dont_accept_new_work);
	provider_db.upsert(provider_description_to);

	// When no more tasks are happening on old provider shut it down
        when_done(provider_description_from, effect(provider) {provider.terminate();})

	// Regenerate the new swagger based on updated provider_db 
	Papiea.generate_swagger();

	// Will remove all endpoints by the old provider and
	// register all endpoints by the new provider

	// This code removes everything and reregisters everything, but we could be more specific in the real case.
	Papiea.stop_main_server(api);
        Papiea.start_main_server(api); 
}

///// Procedural provider functions

func register_provider_callback (status_diff_signature, callback_url) {
	diff_analyzer.insert(status_diff_signature, callback_url);
}

func register_provider_procedure (procedure_signature, callback_url) {
	procedures.insert(procedure_signature, callback_url);
}

/// Main server
// Registers CRUD endpoints in the Papiea's http server for all known kinds in the provider database
//   Create, Delete and Update transforms into spec_change calls
//   Read directly returns the entity from the db
//   Set_Status directly updates the status

func start_main_server(api) {
	all_providers = provider_db.get_all_providers();
	foreach (provider : all_providers) {
		foreach (kind : provider) {
			
			// Create
			api.post(prefix+kind, func(req, res) {
				spec_version = 0;
				task, err = update_spec(metadata(req), spec(req, spec_version));
				res.setData(task);
				return res;
			});

			// Read
			api.get(prefix+kind, func(req, res) {
				res.setData(get_entity(req));
				return res;
			});

			// Update
			api.put(prefix+kind, func(req, res) {
				task, err = update_spec(metadata(req), spec(req, req.spec_version));
				res.setData(task);
				return res;
			});

			// Delete
			api.delete(prefix+kind, func(req, res) {
				task, err = update_spec(metadata(req), nil);
				res.setData(task);
				return res;
			});
		}

		foreach (procedure : provider.procedures) {
			api.post(prefix+kind+procedure.name, func(req, res) {
				callback_url = procedures.get(procedures.signature);
				if (!validate(req.params, procedure.in)) {
					res.status(400, "Parameter does not conform to swagger");
					return res;
				}
				entity = get_entity(req.entity_uuid);
				
				callback_res = rest.post(callback_url, {entity, req.params});
				if (!validate(callback_res, procedure.out)) {
					res.status(500, "return value from callback does not conform to swagger");
					return res;
				}

				res.setData(callback_res);
				return res;
			})
		}
	}
}


func stop_main_server(api) {
	api.stopAll();
}

func generate_swagger() {
	swagger_yaml = new yaml();
	swagger_yaml.set_project_name("V3 APIs").set_project_version("1.0").etc(...)
	all_providers = provider_db.get_all_providers();
	foreach (provider : all_providers) {
		foreach (kind : provider) {
			swagger_yaml.addEntity (kind.description);
			swagger_yaml.add_post(kind);
			swagger_yaml.add_get(kind);
			swagger_yaml.add_update(kind);
			swagger_yaml.add_delete(kind);
		}

		foreach (procedure : provider.procedures) {
			swagger_yaml.addEntity(procedure.in);
			swagger_yaml.addEntity(procedure.out);
			swagger_yaml.add_post(procedure);
		}
	}

	api.expose_resource("/swagger.yaml". swagger_yaml);
}
