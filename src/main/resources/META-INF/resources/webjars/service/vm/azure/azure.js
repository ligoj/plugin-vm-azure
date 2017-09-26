define(function () {
	var current = {

		/**
		 * Render Azure identifier.
		 */
		renderKey: function (subscription) {
			return current.$super('renderKey')(subscription, 'service:vm:azure:name');
		},

		configureSubscriptionParameters: function (configuration) {
			current.$super('registerXServiceSelect2')(configuration, 'service:vm:azure:name', 'service/vm/azure/');
		},

		/**
		 * Render Azure portal for this VM.
		 */
		renderFeatures: function (subscription) {
			var result = '';
			if (subscription.parameters && subscription.parameters['service:vm:azure:subscription'] && subscription.parameters['service:vm:azure:subscription'] && subscription.parameters['service:vm:azure:name']) {
				// Add portal link directly to this VM
				result += current.$super('renderServicelink')('home', 'https://portal.azure.com/#resource/subscriptions/'
				 + subscription.parameters['service:vm:azure:subscription'] + '/resourceGroups/'
				 + subscription.parameters['service:vm:azure:resource-group'] + '/providers/Microsoft.Compute/virtualMachines/'
				 + subscription.parameters['service:vm:azure:name'] + '/overview', 'service:vm:azure:portal', null, ' target="_blank"');
			}
			return result;
		},

		/**
		 * Render Azure details : id, name of VM, description, CPU, memory and vApp.
		 */
		renderDetailsKey: function (subscription) {
			var vm = subscription.data.vm;
			return current.$super('generateCarousel')(subscription, [
				[
					'service:vm:azure:name', current.renderKey(subscription)
				],
				[
					'serice:vm:os', vm.os
				],
				[
					'service:vm:resources', current.$super('icon')('sliders') + vm.cpu + ' CPU, ' + formatManager.formatSize((vm.ram || 0) * 1024 * 1024)
				],
				[
					'service:vm:azure:resource-group', current.$super('icon')('server', 'service:vm:azure:resource-group') + subscription.parameters['resource-group']
				],
				[
					'service:vm:azure:subscription', current.$super('icon')('server', 'service:vm:azure:resource-group') + subscription.parameters.subscription
				],
				[
					'service:vm:azure:location', current.$super('icon')('server', 'service:vm:azure:resource-group') + vm.location
				]
			], 1);
		}
	};
	return current;
});